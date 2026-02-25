#!/usr/bin/env bb
;; tests/test_art19_mcp.clj
;;
;; Integration tests for art19-mcp.
;;
;; Philosophy (same as mcp-injector):
;;   - Real http-kit servers, no mocks
;;   - Full request/response cycle every test
;;   - One fake server: art19-api-sim (mimics https://art19.com)
;;   - art19-mcp server started pointing at fake API
;;   - Tests hit /mcp directly with JSON-RPC
;;   - No real credentials needed
;;
;; Run: bb tests/test_art19_mcp.clj

(require '[org.httpkit.server :as http-server]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.test :refer [deftest is testing use-fixtures run-tests]]
         '[clojure.string :as str])

;; Load the MCP server ns
(load-file "art19_mcp.bb")

;; ─── Fake ART19 API Server ───────────────────────────────────────────────────
;; Real http-kit server that mimics https://art19.com.
;; Returns JSON:API-shaped responses. Tracks received requests.
;; Tests can inspect received-requests to verify what was sent.

(def ^:private fake-api-state (atom nil))

(def ^:private fixture-data
  {:series
   [{:id "s-001" :type "series"
     :attributes {:title "Linux Unplugged" :slug "linux-unplugged" :status "active"}}
    {:id "s-002" :type "series"
     :attributes {:title "Coder Radio" :slug "coder-radio" :status "active"}}]

   :episodes
   [{:id "ep-001" :type "episodes"
     :attributes {:title "Episode 600" :status "published"
                  :published true :released_at "2026-01-01T12:00:00Z" :duration 3600}
     :relationships {:series {:data {:id "s-001" :type "series"}}
                     :feed_items {:data [{:id "fi-001" :type "feed_items"}]}}}
    {:id "ep-002" :type "episodes"
     :attributes {:title "Episode 599" :status "draft"
                  :published false :released_at nil :duration 2400}
     :relationships {:series {:data {:id "s-001" :type "series"}}}}]

   :credits
   [{:id "cr-001" :type "credits"
     :attributes {:type "HostCredit"}
     :relationships {:creditable {:data {:id "ep-001" :type "episodes"}}
                     :person     {:data {:id "p-001" :type "people"}}}}]

   :people
   [{:id "p-001" :type "people"
     :attributes {:first_name "Chris" :last_name "Fisher" :full_name "Chris Fisher"}}
    {:id "p-002" :type "people"
     :attributes {:first_name "Wes" :last_name "Payne" :full_name "Wes Payne"}}]

   :episode_versions
   [{:id "v-001" :type "episode_versions"
     :attributes {:processing_status "complete" :source_url "https://cdn.example.com/ep600.mp3"
                  :created_at "2026-01-01T10:00:00Z"}
     :relationships {:episode {:data {:id "ep-001" :type "episodes"}}}}]

   :seasons
   [{:id "sn-001" :type "seasons"
     :attributes {:title "Season 24" :number 24}}]

   :marker_points
   [{:id "mp-001" :type "marker_points"
     :attributes {:position_type 0 :position_type_name "preroll" :start_position nil}}]

   :feed_items
   [{:id "fi-001" :type "feed_items"
     :attributes {:title "Bonus Episode" :status "draft" :published false :itunes_type "bonus"
                  :enclosure_url "https://rss.art19.com/episodes/bonus-episode.mp3"}
     :relationships {:episode {:data {:id "ep-001" :type "episodes"}}
                     :series {:data {:id "s-001" :type "series"}}
                     :feed {:data {:id "f-001" :type "feeds"}}}}
    {:id "fi-002" :type "feed_items"
     :attributes {:title "Main Feed Item" :status "published" :published true :itunes_type "full"
                  :enclosure_url "https://rss.art19.com/episodes/main-episode.mp3"}
     :relationships {:episode {:data {:id "ep-001" :type "episodes"}}
                     :series {:data {:id "s-001" :type "series"}}
                     :feed {:data {:id "f-001" :type "feeds"}}}}]

   :feeds
   [{:id "f-001" :type "feeds"
     :attributes {:title "Main Feed" :slug "main-feed"}}]

   :media_assets
   [{:id "ma-001" :type "media_assets"
     :attributes {:content_type "audio/mpeg"
                  :file_name "episode.mp3" :file_size 52428800
                  :duration_in_ms 3600500 :url "https://cdn.art19.com/episodes/ep-001/episode.mp3"
                  :asset_type "original"}
     :relationships {:episode_version {:data {:id "v-001" :type "episode_versions"}}}}]})

(defn- jsonapi-list [items & [meta]]
  (cond-> {:data items :links {:next nil}}
    meta (assoc :meta meta)))

(defn- jsonapi-item [item]
  {:data item})

(defn- jsonapi-error [status detail]
  {:errors [{:status (str status) :detail detail}]})

(defn- fake-api-handler [request]
  (let [method  (:request-method request)
        uri     (:uri request)
        raw-body (:body request)
        body    (when (seq raw-body)
                  (try (json/parse-string raw-body true)
                       (catch Exception e nil)))
        query-params (when-let [qs (:query-string request)]
                       (let [params (atom {})]
                         (doseq [pair (clojure.string/split qs #"&")]
                           (let [[k v] (clojure.string/split pair #"=")]
                             (swap! params assoc k v)))
                         @params))
        _       (swap! (:received-requests @fake-api-state) conj
                       {:method method :uri uri :body body :query-string (:query-string request)})
        respond (fn [status data]
                  {:status  status
                   :headers {"Content-Type" "application/vnd.api+json"}
                   :body    (json/generate-string data)})]

    (cond
      ;; GET /series?filter[slug]=...
      (and (= method :get) (str/starts-with? uri "/series"))
      (if (re-find #"/series/[^?]+" uri)
        (let [id (second (re-find #"/series/([^?]+)" uri))
              item (first (filter #(or (= (:id %) id)
                                       (= (get-in % [:attributes :slug]) id))
                                  (:series fixture-data)))]
          (if item
            (respond 200 (jsonapi-item item))
            (respond 404 (jsonapi-error 404 "Series not found"))))
        ;; list — honour filter[slug]
        (let [slug (get (:query-params request) "filter[slug]")
              items (if slug
                      (filter #(= (get-in % [:attributes :slug]) slug)
                              (:series fixture-data))
                      (:series fixture-data))]
          (respond 200 (jsonapi-list (vec items)))))

      ;; GET/POST/PATCH/DELETE /episodes
      (str/starts-with? uri "/episodes")
      (cond
        ;; /episodes/{id}/next_sibling - must check BEFORE general /episodes/{id}
        (and (= method :get) (re-find #"/episodes/[^/]+/next_sibling" uri))
        (let [ep-id (second (re-find #"/episodes/([^/]+)/next_sibling" uri))]
          (respond 200 (jsonapi-item
                        {:id "ep-next" :type "episodes"
                         :attributes {:title (str "Episode after " ep-id) :status "draft"}})))

        ;; /episodes/{id}/previous_sibling - must check BEFORE general /episodes/{id}
        (and (= method :get) (re-find #"/episodes/[^/]+/previous_sibling" uri))
        (let [ep-id (second (re-find #"/episodes/([^/]+)/previous_sibling" uri))]
          (respond 200 (jsonapi-item
                        {:id "ep-prev" :type "episodes"
                         :attributes {:title (str "Episode before " ep-id) :status "draft"}})))

        (and (= method :get) (re-find #"/episodes/[^?/]+" uri))
        (let [id   (second (re-find #"/episodes/([^?/]+)" uri))
              item (first (filter #(= (:id %) id) (:episodes fixture-data)))]
          (if item
            (respond 200 (jsonapi-item item))
            (respond 404 (jsonapi-error 404 "Episode not found"))))

        (and (= method :get) (= uri "/episodes"))
        (let [series-id (get (:query-params request) "filter[series_id]")
              items (if series-id
                      (filter #(= (get-in % [:relationships :series :data :id]) series-id)
                              (:episodes fixture-data))
                      (:episodes fixture-data))]
          (respond 200 (jsonapi-list (vec items))))

        (= method :post)
        (let [title (get-in body [:data :attributes :title])]
          (respond 201 (jsonapi-item
                        {:id "ep-new" :type "episodes"
                         :attributes {:title title :status "draft" :published false}})))

        (= method :patch)
        (let [id    (second (re-find #"/episodes/([^?/]+)" uri))
              attrs (get-in body [:data :attributes])
              base  (first (filter #(= (:id %) id) (:episodes fixture-data)))
              merged (update base :attributes merge attrs)]
          (respond 200 (jsonapi-item (or merged {:id id :type "episodes" :attributes attrs}))))

        (= method :delete)
        {:status 204 :headers {} :body ""}

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; GET/POST/DELETE /credits
      (str/starts-with? uri "/credits")
      (cond
        (= method :get)
        (let [ep-id (get (:query-params request) "filter[creditable_id]")
              items (if ep-id
                      (filter #(= (get-in % [:relationships :creditable :data :id]) ep-id)
                              (:credits fixture-data))
                      (:credits fixture-data))]
          (respond 200 (jsonapi-list (vec items))))

        (= method :post)
        (respond 201 (jsonapi-item
                      {:id "cr-new" :type "credits"
                       :attributes {:type (get-in body [:data :attributes :type])}
                       :relationships (get-in body [:data :relationships])}))

        (= method :patch)
        (respond 200 (jsonapi-item
                      {:id "cr-001" :type "credits"
                       :attributes {:type (get-in body [:data :attributes :type])}}))

        (= method :delete)
        {:status 204 :headers {} :body ""}

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; GET/POST /people
      (str/starts-with? uri "/people")
      (cond
        (and (= method :get) (re-find #"/people/[^?]+" uri))
        (let [id   (second (re-find #"/people/([^?]+)" uri))
              item (first (filter #(= (:id %) id) (:people fixture-data)))]
          (if item
            (respond 200 (jsonapi-item item))
            (respond 404 (jsonapi-error 404 "Person not found"))))

        (= method :get)
        (let [name-filter (get (:query-params request) "filter[name]")
              items (if name-filter
                      (filter #(str/includes?
                                (str/lower-case (or (get-in % [:attributes :full_name]) ""))
                                (str/lower-case name-filter))
                              (:people fixture-data))
                      (:people fixture-data))]
          (respond 200 (jsonapi-list (vec items))))

        (= method :post)
        (respond 201 (jsonapi-item
                      {:id "p-new" :type "people"
                       :attributes {:first_name (get-in body [:data :attributes :first_name])
                                    :last_name  (get-in body [:data :attributes :last_name])
                                    :full_name  (str (get-in body [:data :attributes :first_name])
                                                     " "
                                                     (get-in body [:data :attributes :last_name]))}}))

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /episode_versions
      (str/starts-with? uri "/episode_versions")
      (cond
        (and (= method :get) (re-find #"/episode_versions/[^?]+" uri))
        (let [id   (second (re-find #"/episode_versions/([^?]+)" uri))
              item (first (filter #(= (:id %) id) (:episode_versions fixture-data)))]
          (if item
            (respond 200 (jsonapi-item item))
            (respond 404 (jsonapi-error 404 "Version not found"))))

        (= method :get)
        (respond 200 (jsonapi-list (:episode_versions fixture-data)))

        (= method :post)
        (respond 201 (jsonapi-item
                      {:id "v-new" :type "episode_versions"
                       :attributes {:processing_status "draft"
                                    :source_url (get-in body [:data :attributes :source_url])}}))

        (= method :patch)
        (let [id (second (re-find #"/episode_versions/([^?]+)" uri))
              attrs (get-in body [:data :attributes])
              base {:id id :type "episode_versions" :attributes {:processing_status "draft"}}
              merged (update base :attributes merge attrs)]
          (respond 200 (jsonapi-item merged)))

        (= method :delete)
        {:status 204 :headers {} :body ""}

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /seasons
      (str/starts-with? uri "/seasons")
      (cond
        (and (= method :get) (re-find #"/seasons/[^?]+" uri))
        (let [id   (second (re-find #"/seasons/([^?]+)" uri))
              item (first (filter #(= (:id %) id) (:seasons fixture-data)))]
          (if item (respond 200 (jsonapi-item item))
              (respond 404 (jsonapi-error 404 "Season not found"))))

        (= method :get)
        (respond 200 (jsonapi-list (:seasons fixture-data)))

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /marker_points
      (str/starts-with? uri "/marker_points")
      (cond
        (= method :get)   (respond 200 (jsonapi-list (:marker_points fixture-data)))
        (= method :post)  (respond 201 (jsonapi-item
                                        {:id "mp-new" :type "marker_points"
                                         :attributes {:position_type (get-in body [:data :attributes :position_type])
                                                      :position_type_name "midroll"
                                                      :start_position (get-in body [:data :attributes :start_position])}}))
        (= method :delete) {:status 204 :headers {} :body ""}
        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /images
      (str/starts-with? uri "/images")
      (cond
        (= method :get)
        (respond 200 (jsonapi-list []))

        (= method :post)
        (respond 201 (jsonapi-item
                      {:id "img-new" :type "images"
                       :attributes {:status "uploaded"
                                    :source_url (get-in body [:data :attributes :source_url])}}))

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /media_assets?attachment_id=...&attachment_type=...
      (str/starts-with? uri "/media_assets")
      (let [att-id   (get query-params "attachment_id")
            att-type (get query-params "attachment_type")
            items    (if (and att-id att-type)
                       (filter #(= (get-in % [:relationships :episode_version :data :id]) att-id)
                               (:media_assets fixture-data))
                       (:media_assets fixture-data))]
        (respond 200 (jsonapi-list items)))

      ;; /feed_items
      (str/starts-with? uri "/feed_items")
      (cond
        (and (= method :get) (re-find #"/feed_items/[^?/]+" uri))
        (let [id   (second (re-find #"/feed_items/([^?/]+)" uri))
              item (first (filter #(= (:id %) id) (:feed_items fixture-data)))]
          (if item
            (respond 200 (jsonapi-item item))
            (respond 404 (jsonapi-error 404 "Feed item not found"))))

        (= method :get)
        (let [ep-id (get (:query-params request) "episode_id")
              series-id (get (:query-params request) "series_id")
              feed-id (get (:query-params request) "feed_id")
              items (or (when ep-id
                          (filter #(= (get-in % [:relationships :episode :data :id]) ep-id)
                                  (:feed_items fixture-data)))
                        (when series-id
                          (filter #(= (get-in % [:relationships :series :data :id]) series-id)
                                  (:feed_items fixture-data)))
                        (when feed-id
                          (filter #(= (get-in % [:relationships :feed :data :id]) feed-id)
                                  (:feed_items fixture-data)))
                        (:feed_items fixture-data))]
          (respond 200 (jsonapi-list (vec items))))

        (= method :post)
        (let [title (get-in body [:data :attributes :title])]
          (respond 201 (jsonapi-item
                        {:id "fi-new" :type "feed_items"
                         :attributes {:title title :status "draft" :published false}})))

        (= method :patch)
        (let [id    (second (re-find #"/feed_items/([^?/]+)" uri))
              attrs (get-in body [:data :attributes])
              base  (first (filter #(= (:id %) id) (:feed_items fixture-data)))
              merged (update base :attributes merge attrs)]
          (respond 200 (jsonapi-item (or merged {:id id :type "feed_items" :attributes attrs}))))

        (= method :delete)
        {:status 204 :headers {} :body ""}

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      :else (respond 404 (jsonapi-error 404 (str "Unknown path: " uri))))))

(defn start-fake-api []
  (let [received (atom [])
        srv      (http-server/run-server
                  (fn [req]
                    ;; Body is a stream, read it once and store it
                    (let [body-str (when (:body req) (slurp (:body req)))]
                      (fake-api-handler (assoc req :body body-str))))
                  {:port 0})
        port     (:local-port (meta srv))]
    (reset! fake-api-state {:server srv :port port :received-requests received})
    {:port port :stop srv :received-requests received}))

(defn stop-fake-api [{:keys [stop]}]
  (stop)
  (reset! fake-api-state nil))

;; ─── MCP Client Helpers ─────────────────────────────────────────────────────
;; Thin wrappers to call the MCP server like mcp-injector would.

(defn mcp-init! [base-url]
  (let [resp (http/post base-url
                        {:headers {"Content-Type" "application/json"
                                   "Accept"       "application/json"}
                         :body    (json/generate-string
                                   {:jsonrpc "2.0" :id "init" :method "initialize"
                                    :params  {:protocolVersion "2025-03-26"
                                              :capabilities    {}
                                              :clientInfo      {:name "test" :version "0"}}})})
        sid (or (get-in resp [:headers "mcp-session-id"])
                (get-in resp [:headers :mcp-session-id])
                (some (fn [[k v]] (when (= "mcp-session-id" (str/lower-case (name k))) v))
                      (:headers resp)))]
    (when-not sid (throw (ex-info "No session ID in initialize response" {:resp resp})))
    ;; Send initialized notification
    (http/post base-url
               {:headers {"Content-Type"  "application/json"
                          "Mcp-Session-Id" sid}
                :body    (json/generate-string
                          {:jsonrpc "2.0" :method "notifications/initialized" :params {}})})
    sid))

(defn mcp-call! [base-url sid method params]
  (let [resp (http/post base-url
                        {:headers {"Content-Type"  "application/json"
                                   "Accept"        "application/json"
                                   "Mcp-Session-Id" sid}
                         :body    (json/generate-string
                                   {:jsonrpc "2.0"
                                    :id      (str (java.util.UUID/randomUUID))
                                    :method  method
                                    :params  params})})]
    (json/parse-string (:body resp) true)))

(defn tool-call! [base-url sid tool-name args]
  (mcp-call! base-url sid "tools/call" {:name tool-name :arguments args}))

(defn tool-result [rpc-response]
  "Extract text content from a tools/call response, parsed as JSON if possible."
  (let [text (get-in rpc-response [:result :content 0 :text])]
    (when text
      (try (json/parse-string text true)
           (catch Exception _ text)))))

(defn tool-error? [rpc-response]
  (true? (get-in rpc-response [:result :isError])))

;; ─── Test Fixtures ──────────────────────────────────────────────────────────

(def ^:dynamic *fake-api* nil)
(def ^:dynamic *mcp-url* nil)
(def ^:dynamic *mcp-srv* nil)
(def ^:dynamic *session-id* nil)

(defn integration-fixture [test-fn]
  (let [fake-api (start-fake-api)
        ;; Fake config pointing at our fake API server
        config   {:api-token "test-token" :api-credential "test-cred"}
        ;; Override base-url in art19-mcp ns to point at fake API
        real-base art19-mcp/base-url
        fake-base (str "http://127.0.0.1:" (:port fake-api))
        ;; Start art19-mcp server
        mcp-srv  (http-server/run-server
                  (fn [req] (art19-mcp/handler req config))
                  {:port 0 :ip "127.0.0.1"})
        mcp-port (:local-port (meta mcp-srv))
        mcp-url  (str "http://127.0.0.1:" mcp-port "/mcp")]
    ;; Patch base-url to point at fake API
    (alter-var-root #'art19-mcp/base-url (constantly fake-base))
    (try
      (let [sid (mcp-init! mcp-url)]
        (binding [*fake-api*   fake-api
                  *mcp-url*    mcp-url
                  *mcp-srv*    mcp-srv
                  *session-id* sid]
          (test-fn)))
      (finally
        (alter-var-root #'art19-mcp/base-url (constantly real-base))
        (mcp-srv)
        (stop-fake-api fake-api)))))

(use-fixtures :once integration-fixture)

(defn clear-requests-fixture [test-fn]
  (reset! (:received-requests *fake-api*) [])
  (test-fn))

(use-fixtures :each clear-requests-fixture)

;; ─── Tests: MCP Protocol ────────────────────────────────────────────────────

(deftest test-initialize-returns-session
  (testing "New initialize request creates a new session and returns Mcp-Session-Id"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"}
                           :body    (json/generate-string
                                     {:jsonrpc "2.0" :id "1" :method "initialize"
                                      :params  {:protocolVersion "2025-03-26"
                                                :capabilities {} :clientInfo {:name "t" :version "0"}}})})
          body (json/parse-string (:body resp) true)
          sid  (or (get-in resp [:headers "mcp-session-id"])
                   (get-in resp [:headers :mcp-session-id])
                   (some (fn [[k v]] (when (= "mcp-session-id" (str/lower-case (name k))) v))
                         (:headers resp)))]
      (is (= 200 (:status resp)))
      (is (= "2025-03-26" (get-in body [:result :protocolVersion])))
      (is (= "art19-mcp" (get-in body [:result :serverInfo :name])))
      (is (some? sid)))))

(deftest test-tools-list
  (testing "tools/list returns all tools with names and schemas"
    (let [resp (mcp-call! *mcp-url* *session-id* "tools/list" {})
          tools (get-in resp [:result :tools])]
      (is (= 34 (count tools))) ; was 29, now 34 (added 5 feed_items tools)
      (is (every? :name tools))
      (is (every? :description tools))
      (is (every? :inputSchema tools))
      ;; Spot-check a few
      (is (some #(= "list_episodes" (:name %)) tools))
      (is (some #(= "publish_episode" (:name %)) tools))
      (is (some #(= "create_episode_version" (:name %)) tools))
      (is (some #(= "add_credit" (:name %)) tools))
      ;; Previous new tools
      (is (some #(= "update_episode_version" (:name %)) tools))
      (is (some #(= "get_episode_next_sibling" (:name %)) tools))
      (is (some #(= "get_episode_previous_sibling" (:name %)) tools))
      (is (some #(= "upload_image" (:name %)) tools))
      ;; Feed items tools
      (is (some #(= "list_feed_items" (:name %)) tools))
      (is (some #(= "get_feed_item" (:name %)) tools))
      (is (some #(= "create_feed_item" (:name %)) tools))
      (is (some #(= "update_feed_item" (:name %)) tools))
      (is (some #(= "delete_feed_item" (:name %)) tools)))))

(deftest test-invalid-session-rejected
  (testing "Request with missing/invalid session ID returns 400"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type"   "application/json"
                                     "Mcp-Session-Id" "not-a-real-session"}
                           :throw   false
                           :body    (json/generate-string
                                     {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})})]
      (is (= 400 (:status resp))))))

(deftest test-unknown-method-returns-error
  (testing "Unknown JSON-RPC method returns -32601 Method not found"
    (let [resp (mcp-call! *mcp-url* *session-id* "bogus/method" {})
          err  (:error resp)]
      (is (= -32601 (:code err))))))

;; ─── Tests: Series ──────────────────────────────────────────────────────────

(deftest test-list-series
  (testing "list_series returns all series"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_series" {}))]
      (is (vector? (:series result)))
      (is (= 2 (count (:series result))))
      (is (some #(= "linux-unplugged" (:slug %)) (:series result))))))

(deftest test-get-series-by-slug
  (testing "get_series accepts JB alias and returns series with seasons"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_series"
                                          {:series_slug "lu"}))]
      (is (= "s-001" (get-in result [:data :id])))
      (is (= "Linux Unplugged" (get-in result [:data :attributes :title]))))))

(deftest test-get-series-slug-resolution
  (testing "All JB aliases resolve correctly through the tool"
    ;; Just verify lu → linux-unplugged hits the right API path
    (reset! (:received-requests *fake-api*) [])
    (tool-call! *mcp-url* *session-id* "get_series" {:series_slug "lu"})
    (let [reqs @(:received-requests *fake-api*)
          ;; First request is slug resolution (GET /series?filter[slug]=linux-unplugged)
          ;; Second is GET /series/s-001
          slug-req (first reqs)]
      (is (or (str/includes? (str (:uri slug-req)) "linux-unplugged")
              (str/includes? (str (:query-string slug-req)) "linux-unplugged"))))))

;; ─── Tests: Episodes ────────────────────────────────────────────────────────

(deftest test-list-episodes-by-series-slug
  (testing "list_episodes with JB alias returns episodes for that series"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_episodes"
                                          {:series_slug "lu"}))]
      (is (vector? (:episodes result)))
      (is (pos? (count (:episodes result))))
      (is (every? :id (:episodes result)))
      (is (every? :title (:episodes result))))))

(deftest test-list-episodes-filter-published
  (testing "list_episodes passes published filter to API"
    (reset! (:received-requests @fake-api-state) [])
    (tool-call! *mcp-url* *session-id* "list_episodes"
                {:series_slug "lu" :published true})
    (let [reqs (filter #(str/includes? (str (:uri %)) "episodes")
                       @(:received-requests @fake-api-state))
          ep-req (last reqs)]
      (is (some? ep-req))
      (is (or (str/includes? (str (:uri ep-req)) "published")
              (str/includes? (str (:query-string ep-req)) "published"))))))

(deftest test-get-episode
  (testing "get_episode returns full episode details"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_episode"
                                          {:episode_id "ep-001"}))]
      (is (= "ep-001" (get-in result [:data :id])))
      (is (= "Episode 600" (get-in result [:data :attributes :title]))))))

(deftest test-get-episode-not-found
  (testing "get_episode with bad ID surfaces error without crashing"
    (let [resp (tool-call! *mcp-url* *session-id* "get_episode"
                           {:episode_id "does-not-exist"})]
      (is (tool-error? resp)))))

(deftest test-create-episode
  (testing "create_episode POSTs correct body and returns new episode"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_episode"
                                          {:series_slug "lu" :title "Test Episode"}))]
      (is (= "ep-new" (get-in result [:data :id])))
      (is (= "draft" (get-in result [:data :attributes :status])))
      ;; Verify what was sent to fake API
      (let [post-req (last (filter #(and (= (:method %) :post)
                                         (= (:uri %) "/episodes"))
                                   @(:received-requests *fake-api*)))]
        (is (= "Test Episode" (get-in post-req [:body :data :attributes :title])))
        (is (= "s-001" (get-in post-req [:body :data :relationships :series :data :id])))))))

(deftest test-create-episode-requires-title
  (testing "create_episode without title returns error"
    (let [resp (tool-call! *mcp-url* *session-id* "create_episode"
                           {:series_slug "lu"})]
      ;; Should get an error back (NullPointerException or similar caught at boundary)
      (is (tool-error? resp)))))

(deftest test-update-episode
  (testing "update_episode PATCHes correct fields"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "update_episode"
                                          {:episode_id "ep-001"
                                           :title "Updated Title"
                                           :description "New description"}))]
      (is (some? result))
      (let [patch-req (last (filter #(and (= (:method %) :patch)
                                          (str/includes? (or (:uri %) "") "episodes"))
                                    @(:received-requests *fake-api*)))]
        (is (= "Updated Title" (get-in patch-req [:body :data :attributes :title])))
        (is (= "New description" (get-in patch-req [:body :data :attributes :description])))))))

(deftest test-publish-episode
  (testing "publish_episode sends published=true"
    (tool-call! *mcp-url* *session-id* "publish_episode"
                {:episode_id "ep-001"
                 :released_at "2026-03-01T12:00:00Z"})
    (let [patch-req (last (filter #(and (= (:method %) :patch)
                                        (str/includes? (or (:uri %) "") "episodes"))
                                  @(:received-requests *fake-api*)))]
      (is (= true (get-in patch-req [:body :data :attributes :published])))
      (is (= "2026-03-01T12:00:00Z"
             (get-in patch-req [:body :data :attributes :released_at]))))))

(deftest test-delete-episode
  (testing "delete_episode sends DELETE and returns deleted ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "delete_episode"
                                          {:episode_id "ep-001"}))]
      (is (= "ep-001" (:deleted result))))))

;; ─── Tests: Credits ─────────────────────────────────────────────────────────

(deftest test-list-credits
  (testing "list_credits returns credits for episode with role and person_id"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_credits"
                                          {:episode_id "ep-001"}))]
      (is (vector? (:credits result)))
      (is (= "HostCredit" (:role (first (:credits result)))))
      (is (= "p-001" (:person_id (first (:credits result))))))))

(deftest test-add-credit
  (testing "add_credit POSTs with correct role and person/episode relationships"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "add_credit"
                                          {:episode_id "ep-001"
                                           :person_id  "p-002"
                                           :role       "CoHostCredit"}))]
      (is (= "cr-new" (get-in result [:data :id])))
      (let [post-req (last (filter #(and (= (:method %) :post)
                                         (= (:uri %) "/credits"))
                                   @(:received-requests *fake-api*)))
            req-body (:body post-req)]
        (is (= "CoHostCredit" (get-in req-body [:data :attributes :type])))
        (is (= "ep-001" (get-in req-body [:data :relationships :creditable :data :id])))
        (is (= "p-002" (get-in req-body [:data :relationships :person :data :id])))))))

(deftest test-update-credit
  (testing "update_credit PATCHes with correct role/type"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "update_credit"
                                          {:credit_id "cr-001"
                                           :role "ProducerCredit"}))]
      (is (= "cr-001" (get-in result [:data :id])))
      (let [patch-req (last (filter #(and (= (:method %) :patch)
                                          (str/includes? (or (:uri %) "") "credits"))
                                    @(:received-requests *fake-api*)))]
        (is (= "ProducerCredit" (get-in patch-req [:body :data :attributes :type])))))))

(deftest test-remove-credit
  (testing "remove_credit DELETEs and returns removed ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "remove_credit"
                                          {:credit_id "cr-001"}))]
      (is (= "cr-001" (:removed result))))))

;; ─── Tests: People ──────────────────────────────────────────────────────────

(deftest test-search-people
  (testing "search_people filters by name and returns matches"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "search_people"
                                          {:q "Chris"}))]
      (is (vector? (:people result)))
      (is (some #(= "Chris Fisher" (:full_name %)) (:people result))))))

(deftest test-get-person
  (testing "get_person returns person details by ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_person"
                                          {:person_id "p-001"}))]
      (is (= "p-001" (get-in result [:data :id])))
      (is (= "Chris Fisher" (get-in result [:data :attributes :full_name]))))))

(deftest test-create-person
  (testing "create_person POSTs first/last name and returns new person"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_person"
                                          {:first_name "Jupiter"
                                           :last_name  "Broadcasting"}))]
      (is (= "p-new" (get-in result [:data :id])))
      (is (= "Jupiter Broadcasting" (get-in result [:data :attributes :full_name]))))))

;; ─── Tests: Episode Versions ────────────────────────────────────────────────

(deftest test-list-episode-versions
  (testing "list_episode_versions returns versions for episode"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_episode_versions"
                                          {:episode_id "ep-001"}))]
      (is (vector? (:versions result)))
      (is (= "v-001" (:id (first (:versions result)))))
      (is (= "complete" (:processing_status (first (:versions result))))))))

(deftest test-create-episode-version
  (testing "create_episode_version POSTs source_url and returns draft version"
    (let [url    "https://cdn.jb.com/ep601.mp3"
          result (tool-result (tool-call! *mcp-url* *session-id* "create_episode_version"
                                          {:episode_id "ep-001" :source_url url}))]
      (is (= "v-new" (get-in result [:data :id])))
      (is (= "draft" (get-in result [:data :attributes :processing_status]))) ; ART19 starts in draft
      (let [post-req (last (filter #(and (= (:method %) :post)
                                         (str/includes? (or (:uri %) "") "episode_versions"))
                                   @(:received-requests *fake-api*)))]
        (is (= url (get-in post-req [:body :data :attributes :source_url])))
        (is (= "ep-001" (get-in post-req [:body :data :relationships :episode :data :id])))))))

(deftest test-get-episode-version
  (testing "get_episode_version returns version details"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_episode_version"
                                          {:version_id "v-001"}))]
      (is (= "v-001" (get-in result [:data :id])))
      (is (= "complete" (get-in result [:data :attributes :processing_status]))))))

(deftest test-delete-episode-version
  (testing "delete_episode_version DELETEs and returns deleted ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "delete_episode_version"
                                          {:version_id "v-001"}))]
      (is (= "v-001" (:deleted result))))))

;; ─── Tests: Episode Versions Update ──────────────────────────────────────────

(deftest test-update-episode-version
  (testing "update_episode_version PATCHes processing_status to submitted"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "update_episode_version"
                                          {:version_id "v-001"
                                           :processing_status "submitted"
                                           :status_on_completion "active"}))]
      (is (= "v-001" (get-in result [:data :id])))
      (let [patch-req (last (filter #(and (= (:method %) :patch)
                                          (str/includes? (or (:uri %) "") "episode_versions"))
                                    @(:received-requests *fake-api*)))]
        (is (= "submitted" (get-in patch-req [:body :data :attributes :processing_status])))
        (is (= "active" (get-in patch-req [:body :data :attributes :status_on_completion])))))))

;; ─── Tests: Episode Siblings ─────────────────────────────────────────────────

(deftest test-get-episode-next-sibling
  (testing "get_episode_next_sibling returns next episode"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_episode_next_sibling"
                                          {:episode_id "ep-001"}))]
      (is (= "ep-next" (get-in result [:data :id])))
      (is (str/includes? (get-in result [:data :attributes :title]) "after ep-001")))))

(deftest test-get-episode-previous-sibling
  (testing "get_episode_previous_sibling returns previous episode"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_episode_previous_sibling"
                                          {:episode_id "ep-001"}))]
      (is (= "ep-prev" (get-in result [:data :id])))
      (is (str/includes? (get-in result [:data :attributes :title]) "before ep-001")))))

;; ─── Tests: Image Upload ───────────────────────────────────────────────────

(deftest test-upload-image
  (testing "upload_image POSTs source_url and series_id (bucket)"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "upload_image"
                                          {:source_url "https://cdn.jb.com/artwork.jpg"
                                           :series_id "s-001"}))]
      (is (= "img-new" (get-in result [:data :id])))
      (is (= "uploaded" (get-in result [:data :attributes :status])))
      (let [post-req (last (filter #(and (= (:method %) :post)
                                         (= (:uri %) "/images"))
                                   @(:received-requests *fake-api*)))]
        (is (= "https://cdn.jb.com/artwork.jpg" (get-in post-req [:body :data :attributes :source_url])))
        (is (= "s-001" (get-in post-req [:body :data :relationships :bucket :data :id])))))))

;; ─── Tests: Media Assets ───────────────────────────────────────────────────

(deftest test-list-media-assets
  (testing "list_media_assets returns audio file details (duration_in_ms, file_size, url)"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_media_assets"
                                          {:attachment_id "v-001"
                                           :attachment_type "EpisodeVersion"}))
          first-asset (first result)]
      (is (= 1 (count result)))
      (is (= "ma-001" (:id first-asset)))
      (is (= 3600500 (get-in first-asset [:attributes :duration_in_ms])))
      (is (= 52428800 (get-in first-asset [:attributes :file_size])))
      (is (= "https://cdn.art19.com/episodes/ep-001/episode.mp3"
             (get-in first-asset [:attributes :url]))))))

;; ─── Tests: Marker Points ───────────────────────────────────────────────────

(deftest test-list-marker-points
  (testing "list_marker_points returns markers for episode version"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_marker_points"
                                          {:episode_version_id "v-001"}))]
      (is (vector? (:marker_points result)))
      (is (= "preroll" (:position_type (first (:marker_points result))))))))

(deftest test-create-marker-point
  (testing "create_marker_point POSTs position_type and returns new marker"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_marker_point"
                                          {:episode_version_id "v-001"
                                           :position_type 1
                                           :start_position 300.0}))]
      (is (= "mp-new" (get-in result [:data :id])))
      (is (= 1 (get-in result [:data :attributes :position_type]))))))

(deftest test-delete-marker-point
  (testing "delete_marker_point DELETEs and returns deleted ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "delete_marker_point"
                                          {:marker_point_id "mp-001"}))]
      (is (= "mp-001" (:deleted result))))))

;; ─── Tests: Feed Items ───────────────────────────────────────────────────

(deftest test-list-feed-items-by-series-id
  (testing "list_feed_items returns feed items for a series"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_feed_items"
                                          {:series_id "s-001"}))]
      (is (vector? (:feed_items result)))
      (is (pos? (count (:feed_items result))))
      (is (every? :id (:feed_items result)))
      (is (every? :enclosure_url (:feed_items result))))))

(deftest test-list-feed-items-by-episode-id
  (testing "list_feed_items filters by episode_id"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_feed_items"
                                          {:episode_id "ep-001"}))]
      (is (vector? (:feed_items result))))))

(deftest test-get-feed-item
  (testing "get_feed_item returns full feed item details"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_feed_item"
                                          {:feed_item_id "fi-001"}))]
      (is (= "fi-001" (get-in result [:data :id])))
      (is (= "Bonus Episode" (get-in result [:data :attributes :title]))))))

(deftest test-get-feed-item-not-found
  (testing "get_feed_item with bad ID surfaces error without crashing"
    (let [resp (tool-call! *mcp-url* *session-id* "get_feed_item"
                           {:feed_item_id "does-not-exist"})]
      (is (tool-error? resp)))))

(deftest test-create-feed-item
  (testing "create_feed_item POSTs correct body and returns new feed item"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_feed_item"
                                          {:series_id "s-001" :title "Test Feed Item"}))]
      (is (= "fi-new" (get-in result [:data :id])))
      (is (= "draft" (get-in result [:data :attributes :status])))
      ;; Verify what was sent to fake API
      (let [post-req (last (filter #(and (= (:method %) :post)
                                         (= (:uri %) "/feed_items"))
                                   @(:received-requests *fake-api*)))]
        (is (= "Test Feed Item" (get-in post-req [:body :data :attributes :title])))
        (is (= "s-001" (get-in post-req [:body :data :relationships :series :data :id])))))))

(deftest test-update-feed-item
  (testing "update_feed_item PATCHes correct fields"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "update_feed_item"
                                          {:feed_item_id "fi-001"
                                           :title "Updated Title"
                                           :description "New description"}))]
      (is (some? result))
      (let [patch-req (last (filter #(and (= (:method %) :patch)
                                          (str/includes? (or (:uri %) "") "feed_items"))
                                    @(:received-requests *fake-api*)))]
        (is (= "Updated Title" (get-in patch-req [:body :data :attributes :title])))
        (is (= "New description" (get-in patch-req [:body :data :attributes :description])))))))

(deftest test-delete-feed-item
  (testing "delete_feed_item sends DELETE and returns deleted ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "delete_feed_item"
                                          {:feed_item_id "fi-001"}))]
      (is (= "fi-001" (:deleted result))))))

;; ─── Tests: Error Propagation ───────────────────────────────────────────────

(deftest test-api-error-surfaced-to-tool
  (testing "ART19 4xx errors surface as MCP isError=true, not crashes"
    (let [resp (tool-call! *mcp-url* *session-id* "get_episode"
                           {:episode_id "no-such-episode"})]
      (is (tool-error? resp))
      (is (str/includes? (get-in resp [:result :content 0 :text]) "Error")))))

(deftest test-tool-call-with-string-arguments
  (testing "Tool call with malformed string args returns proper JSON-RPC error"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"
                                     "Mcp-Session-Id" *session-id*}
                           :body (json/generate-string
                                  {:jsonrpc "2.0"
                                   :id (str (java.util.UUID/randomUUID))
                                   :method "tools/call"
                                   :params {:name "get_episode"
                                            :arguments "malformed-string"}})})
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (contains? body :error))
      (is (= -32600 (get-in body [:error :code])))
      (is (= "Invalid Request" (get-in body [:error :message])))
      (is (= "arguments must be an object" (get-in body [:error :data]))))))

(deftest test-tool-call-with-nil-arguments
  (testing "Tool call with nil arguments works correctly (treated as empty object)"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"
                                     "Mcp-Session-Id" *session-id*}
                           :body (json/generate-string
                                  {:jsonrpc "2.0"
                                   :id (str (java.util.UUID/randomUUID))
                                   :method "tools/call"
                                   :params {:name "get_episode"
                                            :arguments nil}})})
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (contains? body :result))
      (is (tool-error? body))
      (is (str/includes? (get-in body [:result :content 0 :text]) "Error")))))

(deftest test-mcp-robustness
  (testing "Header case-insensitivity"
    (let [sid *session-id*
          resp (http/post *mcp-url*
                          {:headers {"Content-Type"   "application/json"
                                     "mCP-sESSion-iD" sid}
                           :body    (json/generate-string
                                     {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})})]
      (is (= 200 (:status resp)))
      (is (not (str/includes? (:body resp) "Invalid or missing Mcp-Session-Id")))))

  (testing "Malformed JSON body returns 400"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type"   "application/json"
                                     "Mcp-Session-Id" *session-id*}
                           :body    "{invalid json}"
                           :throw   false})]
      (is (= 400 (:status resp)))
      (is (str/includes? (:body resp) "Invalid JSON"))))

  (testing "Missing session ID returns 400"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"}
                           :body    (json/generate-string
                                     {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})
                           :throw   false})]
      (is (= 400 (:status resp)))
      (is (str/includes? (:body resp) "Invalid or missing Mcp-Session-Id")))))

(deftest test-health-endpoint
  (testing "/health returns ok"
    (let [resp (http/get (str (str/replace *mcp-url* "/mcp" "") "/health"))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "ok" (:status body))))))

(deftest test-mcp-missing-body
  (testing "POST to /mcp with no body returns 400 instead of crashing"
    (let [resp (http/post *mcp-url* {:throw false})]
      (is (= 400 (:status resp)))
      (is (str/includes? (:body resp) "Missing request body")))))

;; ─── Runner ─────────────────────────────────────────────────────────────────

(defn -main [& _args]
  (println "\nart19-mcp integration tests")
  (println "===========================")
  (let [{:keys [pass fail error]} (run-tests *ns*)]
    (println (str "\nResults: " pass " passed, " fail " failed, " error " errors"))
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
