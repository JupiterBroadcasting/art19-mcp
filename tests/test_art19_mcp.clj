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
;; Pagination config is set per-test via (reset! pagination-config {...}).

(def ^:private fake-api-state (atom nil))

(def ^:private pagination-config (atom nil))
;; Example: (reset! pagination-config {:path "/series" :pages 2 :items-per-page 1})

(def ^:private version-transition (atom nil))
;; When set, episode_versions GET handler transitions from submitted to active
;; after :polls-before-active polls. Example: (reset! version-transition {:polls-before-active 3})

(def ^:private version-poll-counts (atom {}))
;; Tracks poll count per version when version-transition is active.

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
                     :person {:data {:id "p-001" :type "people"}}}}]

   :people
   [{:id "p-001" :type "people"
     :attributes {:first_name "Chris" :last_name "Fisher" :full_name "Chris Fisher"}}
    {:id "p-002" :type "people"
     :attributes {:first_name "Wes" :last_name "Payne" :full_name "Wes Payne"}}]

   :episode_versions
   [{:id "v-001" :type "episode_versions"
     :attributes {:processing_status "complete" :source_url "https://cdn.example.com/ep600.mp3"
                  :created_at "2026-01-01T10:00:00Z"}
     :relationships {:episode {:data {:id "ep-001" :type "episodes"}}}}
    {:id "v-003" :type "episode_versions"
     :attributes {:processing_status "submitted" :source_url "https://cdn.example.com/processed.mp3"
                  :created_at "2026-01-01T11:00:00Z"}
     :relationships {:episode {:data {:id "ep-001" :type "episodes"}}}}]

   :seasons
   [{:id "sn-001" :type "seasons"
     :attributes {:title "Season 24" :number 24}}]

   :marker_points
   [{:id "mp-001" :type "marker_points"
     :attributes {:position_type 0 :position_type_name "preroll" :start_position nil :end_position nil}}]

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
  (let [method (:request-method request)
        uri (:uri request)
        raw-body (:body request)
        body (when (seq raw-body)
               (try (json/parse-string raw-body true)
                    (catch Exception e nil)))
        query-params (when-let [qs (:query-string request)]
                       (let [params (atom {})]
                         (doseq [pair (clojure.string/split qs #"&")]
                           (let [[k v] (clojure.string/split pair #"=")]
                             (swap! params assoc k v)))
                         @params))
        _ (swap! (:received-requests @fake-api-state) conj
                 {:method method :uri uri :body body :query-string (:query-string request)})
        respond (fn [status data]
                  {:status status
                   :headers {"Content-Type" "application/vnd.api+json"}
                   :body (json/generate-string data)})]

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
        ;; list — honour filter[slug] and q (for resolve-series-id search)
        (let [slug (get query-params "filter[slug]")
              q (get query-params "q")
              items (cond
                      slug (filter #(= (get-in % [:attributes :slug]) slug)
                                   (:series fixture-data))
                      q (filter #(let [s (str/lower-case (get-in % [:attributes :slug] ""))
                                       t (str/lower-case (get-in % [:attributes :title] ""))]
                                   (or (str/includes? s (str/lower-case q))
                                       (str/includes? t (str/lower-case q))))
                                (:series fixture-data))
                      :else (:series fixture-data))]
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
        (let [id (second (re-find #"/episodes/([^?/]+)" uri))
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
        (let [id (second (re-find #"/episodes/([^?/]+)" uri))
              attrs (get-in body [:data :attributes])
              base (first (filter #(= (:id %) id) (:episodes fixture-data)))
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
        (let [id (second (re-find #"/people/([^?]+)" uri))
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
                                    :last_name (get-in body [:data :attributes :last_name])
                                    :full_name (str (get-in body [:data :attributes :first_name])
                                                    " "
                                                    (get-in body [:data :attributes :last_name]))}}))

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /episode_versions
      (str/starts-with? uri "/episode_versions")
      (cond
        (and (= method :get) (re-find #"/episode_versions/[^?]+" uri))
        (let [id (second (re-find #"/episode_versions/([^?]+)" uri))
              item (first (filter #(= (:id %) id) (:episode_versions fixture-data)))]
          (if-let [ts @version-transition]
            ;; Transition mode: track polls, activate after N polls
            (let [polls (get (swap! version-poll-counts update id (fnil inc 0)) id 0)]
              (if (>= polls (:polls-before-active ts 1))
                (respond 200 (jsonapi-item (assoc-in item [:attributes :processing_status] "active")))
                (respond 200 (jsonapi-item item))))
            (if item
              (respond 200 (jsonapi-item item))
              (respond 404 (jsonapi-error 404 "Version not found")))))

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
        (let [id (second (re-find #"/seasons/([^?]+)" uri))
              item (first (filter #(= (:id %) id) (:seasons fixture-data)))]
          (if item (respond 200 (jsonapi-item item))
              (respond 404 (jsonapi-error 404 "Season not found"))))

        (= method :get)
        (respond 200 (jsonapi-list (:seasons fixture-data)))

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /marker_points
      (str/starts-with? uri "/marker_points")
      (cond
        ;; /marker_points/{id} - GET or PATCH single marker
        (and (= method :get) (re-find #"/marker_points/[^?]+" uri))
        (let [id (second (re-find #"/marker_points/([^?]+)" uri))
              item (first (filter #(= (:id %) id) (:marker_points fixture-data)))]
          (if item
            (respond 200 (jsonapi-item item))
            (respond 404 (jsonapi-error 404 "Marker point not found"))))

        (and (= method :patch) (re-find #"/marker_points/[^?]+" uri))
        (let [id (second (re-find #"/marker_points/([^?]+)" uri))
              attrs (get-in body [:data :attributes])
              base (first (filter #(= (:id %) id) (:marker_points fixture-data)))
              merged (update base :attributes merge attrs)]
          (respond 200 (jsonapi-item (or merged {:id id :type "marker_points" :attributes attrs}))))

        (= method :get) (respond 200 (jsonapi-list (:marker_points fixture-data)))
        (= method :post) (respond 201 (jsonapi-item
                                       {:id "mp-new" :type "marker_points"
                                        :attributes {:position_type (get-in body [:data :attributes :position_type])
                                                     :position_type_name "midroll"
                                                     :start_position (get-in body [:data :attributes :start_position])
                                                     :end_position (get-in body [:data :attributes :end_position])}}))
        (= method :delete) {:status 204 :headers {} :body ""}
        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; /marker_point_content_rules
      (str/starts-with? uri "/marker_point_content_rules")
      (cond
        (and (= method :get) (re-find #"/marker_point_content_rules/[^?]+" uri))
        (let [id (second (re-find #"/marker_point_content_rules/([^?]+)" uri))
              item {:id id :type "marker_point_content_rules"
                    :attributes {:priority 1 :content_type "Campaign"}}]
          (respond 200 (jsonapi-item item)))

        (and (= method :patch) (re-find #"/marker_point_content_rules/[^?]+" uri))
        (let [id (second (re-find #"/marker_point_content_rules/([^?]+)" uri))
              attrs (get-in body [:data :attributes])]
          (respond 200 (jsonapi-item {:id id :type "marker_point_content_rules" :attributes attrs})))

        (= method :get) (respond 200 (jsonapi-list []))
        (= method :post) (respond 201 (jsonapi-item
                                       {:id "cr-rule-new" :type "marker_point_content_rules"
                                        :attributes {:priority (get-in body [:data :attributes :priority])
                                                     :content_type (get-in body [:data :attributes :content_type])}}))
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
      (let [att-id (get query-params "attachment_id")
            att-type (get query-params "attachment_type")
            items (if (and att-id att-type)
                    (filter #(= (get-in % [:relationships :episode_version :data :id]) att-id)
                            (:media_assets fixture-data))
                    (:media_assets fixture-data))]
        (respond 200 (jsonapi-list items)))

      ;; /feed_items
      (str/starts-with? uri "/feed_items")
      (cond
        (and (= method :get) (re-find #"/feed_items/[^?/]+" uri))
        (let [id (second (re-find #"/feed_items/([^?/]+)" uri))
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
        (let [id (second (re-find #"/feed_items/([^?/]+)" uri))
              attrs (get-in body [:data :attributes])
              base (first (filter #(= (:id %) id) (:feed_items fixture-data)))
              merged (update base :attributes merge attrs)]
          (respond 200 (jsonapi-item (or merged {:id id :type "feed_items" :attributes attrs}))))

        (= method :delete)
        {:status 204 :headers {} :body ""}

        :else (respond 405 (jsonapi-error 405 "Method not allowed")))

      ;; GET /paginated_resource — test endpoint for fetch-all-pages pagination.
      ;; Uses pagination-config atom: {:total 3 :per-page 1}
      ;; Returns page[n] items with :next link when more pages remain.
      (and (= method :get) (= uri "/paginated_resource"))
      (let [pc @pagination-config
            page-num (try (Integer/parseInt
                           (get query-params "page%5Bnumber%5D" "1"))
                          (catch Exception _ 1))
            per-page (:per-page pc 1)
            total (:total pc 0)
            start (* (dec page-num) per-page)
            items (vec (map (fn [i] {:id (str "pr-" i) :type "paginated_resource"
                                     :attributes {:index i}})
                            (range start (min (+ start per-page) total))))]
        (if (and (seq items) (<= page-num (long (Math/ceil (/ total per-page)))))
          (let [next-url (when (< page-num (long (Math/ceil (/ total per-page))))
                           (str "/paginated_resource?page%5Bnumber%5D=" (inc page-num)))]
            (respond 200 {:data items :links {:next next-url}}))
          (respond 200 {:data [] :links {:next nil}})))

      :else (respond 404 (jsonapi-error 404 (str "Unknown path: " uri))))))

(defn start-fake-api []
  (let [received (atom [])
        srv (http-server/run-server
             (fn [req]
                    ;; Body is a stream, read it once and store it
               (let [body-str (when (:body req) (slurp (:body req)))]
                 (fake-api-handler (assoc req :body body-str))))
             {:port 0})
        port (:local-port (meta srv))]
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
                                   "Accept" "application/json"}
                         :body (json/generate-string
                                {:jsonrpc "2.0" :id "init" :method "initialize"
                                 :params {:protocolVersion "2025-03-26"
                                          :capabilities {}
                                          :clientInfo {:name "test" :version "0"}}})})
        sid (or (get-in resp [:headers "mcp-session-id"])
                (get-in resp [:headers :mcp-session-id])
                (some (fn [[k v]] (when (= "mcp-session-id" (str/lower-case (name k))) v))
                      (:headers resp)))]
    (when-not sid (throw (ex-info "No session ID in initialize response" {:resp resp})))
    ;; Send initialized notification
    (http/post base-url
               {:headers {"Content-Type" "application/json"
                          "Mcp-Session-Id" sid}
                :body (json/generate-string
                       {:jsonrpc "2.0" :method "notifications/initialized" :params {}})})
    sid))

(defn mcp-call! [base-url sid method params]
  (let [resp (http/post base-url
                        {:headers {"Content-Type" "application/json"
                                   "Accept" "application/json"
                                   "Mcp-Session-Id" sid}
                         :body (json/generate-string
                                {:jsonrpc "2.0"
                                 :id (str (java.util.UUID/randomUUID))
                                 :method method
                                 :params params})})]
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
        config {:api-token "test-token" :api-credential "test-cred"}
        ;; Override base-url in art19-mcp ns to point at fake API
        real-base art19-mcp/base-url
        fake-base (str "http://127.0.0.1:" (:port fake-api))
        ;; Start art19-mcp server
        mcp-srv (http-server/run-server
                 (fn [req] (art19-mcp/handler req config))
                 {:port 0 :ip "127.0.0.1"})
        mcp-port (:local-port (meta mcp-srv))
        mcp-url (str "http://127.0.0.1:" mcp-port "/mcp")]
    ;; Patch base-url to point at fake API
    (alter-var-root #'art19-mcp/base-url (constantly fake-base))
    (try
      (let [sid (mcp-init! mcp-url)]
        (binding [*fake-api* fake-api
                  *mcp-url* mcp-url
                  *mcp-srv* mcp-srv
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
                           :body (json/generate-string
                                  {:jsonrpc "2.0" :id "1" :method "initialize"
                                   :params {:protocolVersion "2025-03-26"
                                            :capabilities {} :clientInfo {:name "t" :version "0"}}})})
          body (json/parse-string (:body resp) true)
          sid (or (get-in resp [:headers "mcp-session-id"])
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
      (is (= 42 (count tools))) ; 41 + 1 wait_for_processing
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
                          {:headers {"Content-Type" "application/json"
                                     "Mcp-Session-Id" "not-a-real-session"}
                           :throw false
                           :body (json/generate-string
                                  {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})})]
      (is (= 400 (:status resp))))))

(deftest test-unknown-method-returns-error
  (testing "Unknown JSON-RPC method returns -32601 Method not found"
    (let [resp (mcp-call! *mcp-url* *session-id* "bogus/method" {})
          err (:error resp)]
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

(deftest test-list-seasons
  (testing "list_seasons returns seasons for a series"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_seasons"
                                          {:series_id "s-001"}))]
      (is (vector? (:seasons result)))
      (is (pos? (count (:seasons result))))
      (is (every? :id (:seasons result)))
      (is (every? :title (:seasons result))))))

(deftest test-get-season
  (testing "get_season returns season details"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_season"
                                          {:season_id "sn-001"}))]
      (is (= "sn-001" (get-in result [:data :id])))
      (is (some? (get-in result [:data :attributes :title]))))))

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
             (get-in patch-req [:body :data :attributes :released_at])))))

  (testing "publish_episode with release_immediately and no released_at auto-sets it"
    (let [before (inst-ms (java.time.Instant/now))
          result (tool-result (tool-call! *mcp-url* *session-id* "publish_episode"
                                          {:episode_id "ep-001"
                                           :release_immediately true}))
          after (inst-ms (java.time.Instant/now))
          patch-req (last (filter #(and (= (:method %) :patch)
                                        (str/includes? (or (:uri %) "") "episodes"))
                                  @(:received-requests *fake-api*)))
          sent-at (get-in patch-req [:body :data :attributes :released_at])
          sent-ms (inst-ms (java.time.Instant/parse sent-at))]
      (is (some? result))
      (is (true? (get-in patch-req [:body :data :attributes :published])))
      (is (true? (get-in patch-req [:body :data :attributes :release_immediately])))
      (is (string? sent-at))
      ;; released_at should be between before and after (approx now)
      (is (<= before sent-ms after))
      (is (<= sent-ms after)))))

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
                                           :person_id "p-002"
                                           :role "CoHostCredit"}))]
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
                                           :last_name "Broadcasting"}))]
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
    (let [url "https://cdn.jb.com/ep601.mp3"
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
  (testing "list_marker_points returns markers with full fields"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_marker_points"
                                          {:episode_version_id "v-001"}))]
      (is (vector? (:marker_points result)))
      (let [mp (first (:marker_points result))]
        (is (= "mp-001" (:id mp)))
        (is (= 0 (:position_type mp)))
        (is (= "preroll" (:position_type_name mp)))
        (is (contains? mp :start_position))
        (is (contains? mp :maximum_content_count))
        (is (contains? mp :maximum_content_duration))
        (is (contains? mp :type))
        (is (contains? mp :default_for))))))

(deftest test-create-marker-point
  (testing "create_marker_point POSTs position_type and start_position"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_marker_point"
                                          {:episode_version_id "v-001"
                                           :position_type 1
                                           :start_position 300.0}))]
      (is (= "mp-new" (get-in result [:data :id])))
      (is (= 1 (get-in result [:data :attributes :position_type])))))

  (testing "create_marker_point with end_position for EmbeddedAdPoint"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_marker_point"
                                          {:episode_version_id "v-001"
                                           :position_type 1
                                           :start_position 56.113
                                           :end_position 86.113
                                           :maximum_content_count 1
                                           :maximum_content_duration 60
                                           :type "EmbeddedAdPoint"}))]
      (is (= "mp-new" (get-in result [:data :id])))
      (is (= 86.113 (get-in result [:data :attributes :end_position]))))))

(deftest test-delete-marker-point
  (testing "delete_marker_point DELETEs and returns deleted ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "delete_marker_point"
                                          {:marker_point_id "mp-001"}))]
      (is (= "mp-001" (:deleted result))))))

(deftest test-get-marker-point
  (testing "get_marker_point returns full marker details"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "get_marker_point"
                                          {:marker_point_id "mp-001"}))]
      (is (= "mp-001" (get-in result [:data :id]))))))

(deftest test-update-marker-point
  (testing "update_marker_point PATCHes start_position"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "update_marker_point"
                                          {:marker_point_id "mp-001"
                                           :start_position 600.0}))]
      (is (some? result))
      (let [patch-req (last (filter #(and (= (:method %) :patch)
                                          (str/includes? (or (:uri %) "") "marker_points"))
                                    @(:received-requests *fake-api*)))]
        (is (= 600.0 (get-in patch-req [:body :data :attributes :start_position]))))))

  (testing "update_marker_point PATCHes end_position"
    (let [patch-req-before (last (filter #(and (= (:method %) :patch)
                                               (str/includes? (or (:uri %) "") "marker_points"))
                                         @(:received-requests *fake-api*)))]
      (tool-result (tool-call! *mcp-url* *session-id* "update_marker_point"
                               {:marker_point_id "mp-001"
                                :end_position 120.0}))
      (let [patch-req (last (filter #(and (= (:method %) :patch)
                                          (str/includes? (or (:uri %) "") "marker_points"))
                                    @(:received-requests *fake-api*)))]
        (is (= 120.0 (get-in patch-req [:body :data :attributes :end_position])))))))

(deftest test-list-marker-point-content-rules
  (testing "list_marker_point_content_rules returns content rules"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "list_marker_point_content_rules"
                                          {:marker_point_id "mp-001"}))]
      (is (vector? (:content_rules result))))))

(deftest test-create-marker-point-content-rule
  (testing "create_marker_point_content_rule POSTs with correct body"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "create_marker_point_content_rule"
                                          {:marker_point_id "mp-001"
                                           :priority 1
                                           :content_type "Campaign"}))]
      (is (= "cr-rule-new" (get-in result [:data :id])))
      (is (= 1 (get-in result [:data :attributes :priority])))
      (is (= "Campaign" (get-in result [:data :attributes :content_type]))))))

(deftest test-update-marker-point-content-rule
  (testing "update_marker_point_content_rule PATCHes rule"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "update_marker_point_content_rule"
                                          {:content_rule_id "cr-rule-001"
                                           :priority 2}))]
      (is (some? result)))))

(deftest test-delete-marker-point-content-rule
  (testing "delete_marker_point_content_rule DELETEs and returns deleted ID"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "delete_marker_point_content_rule"
                                          {:content_rule_id "cr-rule-001"}))]
      (is (= "cr-rule-001" (:deleted result))))))

;; ─── Tests: Compound Tool ───────────────────────────────────────────────

(deftest test-prepare-episode-version
  (testing "prepare_episode_version creates version, adds markers, and submits"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                                          {:episode_id "ep-001"
                                           :markers [{:start_position 300.0
                                                      :position_type 1
                                                      :maximum_content_count 2
                                                      :maximum_content_duration 120}]}))]
      (is (some? (:version_id result)))
      (is (= "submitted" (:processing_status result)))
      (is (= "active" (:status_on_completion result)))
      (is (= 1 (:markers_added result)))
      (is (= 1 (:content_rules_created result)))
      (is (vector? (:warnings result))))))

(deftest test-prepare-episode-version-no-markers
  (testing "prepare_episode_version without markers just copies and submits"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                                          {:episode_id "ep-001"}))]
      (is (some? (:version_id result)))
      (is (= "submitted" (:processing_status result)))
      (is (= 0 (:markers_added result))))))

(deftest test-prepare-with-midrolls
  (testing "prepare_episode_version with midrolls generates pre + mid + post template"
    (reset! (:received-requests *fake-api*) [])
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                                          {:episode_id "ep-001"
                                           :midrolls [300.0 900.0]}))
          requests @(:received-requests *fake-api*)
          ;; Find the POST to episode_versions to check copy_marker_points
          create-req (first (filter #(and (= (:method %) :post)
                                          (str/includes? (or (:uri %) "") "episode_versions"))
                                    requests))
          mp-requests (filter #(and (= (:method %) :post)
                                    (str/includes? (or (:uri %) "") "marker_points"))
                              requests)
          by-type (group-by #(get-in % [:body :data :attributes :position_type]) mp-requests)
          pre-roll-req (first (get by-type 0))
          mid-roll-reqs (get by-type 1)
          post-roll-req (first (get by-type 2))]
      (is (some? (:version_id result)))
      (is (= "submitted" (:processing_status result)))
      (is (= 4 (:markers_added result)))
      (is (= 4 (:content_rules_created result)))
      (is (false? (get-in create-req [:body :data :attributes :copy_marker_points])))
      ;; 2+ midrolls -> each gets 90s (default-midroll-multi-duration)
      (is (= 2 (count mid-roll-reqs)))
      (doseq [m mid-roll-reqs]
        (is (= 90 (get-in m [:body :data :attributes :maximum_content_duration]))))
      ;; Pre-roll default duration is 60s (default-pre-roll-duration)
      (is (= 0 (get-in pre-roll-req [:body :data :attributes :position_type])))
      (is (= 60 (get-in pre-roll-req [:body :data :attributes :maximum_content_duration])))
      ;; Post-roll default duration is 180s
      (is (= 2 (get-in post-roll-req [:body :data :attributes :position_type])))
      (is (= 180 (get-in post-roll-req [:body :data :attributes :maximum_content_duration]))))))

(deftest test-prepare-with-midrolls-single
  (testing "prepare_episode_version with a single midroll gets 120s default"
    (reset! (:received-requests *fake-api*) [])
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                                          {:episode_id "ep-001"
                                           :midrolls [600.0]}))
          requests @(:received-requests *fake-api*)
          mp-requests (filter #(and (= (:method %) :post)
                                    (str/includes? (or (:uri %) "") "marker_points"))
                              requests)
          by-type (group-by #(get-in % [:body :data :attributes :position_type]) mp-requests)
          mid-roll-reqs (get by-type 1)]
      (is (some? (:version_id result)))
      (is (= "submitted" (:processing_status result)))
      (is (= 3 (:markers_added result)))
      (is (= 3 (:content_rules_created result)))
      ;; Single midroll -> 120s (default-midroll-duration)
      (is (= 1 (count mid-roll-reqs)))
      (is (= 120 (get-in (first mid-roll-reqs) [:body :data :attributes :maximum_content_duration]))))))

(deftest test-ping-handler
  (testing "ping returns empty result (keeps mcpc bridge alive)"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"
                                     "Accept" "application/json"
                                     "Mcp-Session-Id" *session-id*}
                           :body (json/generate-string
                                  {:jsonrpc "2.0" :id "ping-1" :method "ping" :params {}})})
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= {} (:result body)))
      (is (nil? (:error body))))))

(deftest test-prepare-with-midrolls-empty
  (testing "prepare_episode_version with empty midrolls generates only pre + post"
    (let [result (tool-result (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                                          {:episode_id "ep-001"
                                           :midrolls []}))]
      (is (some? (:version_id result)))
      (is (= 2 (:markers_added result)))
      (is (= 2 (:content_rules_created result))))))

(deftest test-prepare-with-midrolls-and-markers-errors
  (testing "prepare_episode_version with both midrolls and markers returns error"
    (let [resp (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                           {:episode_id "ep-001"
                            :midrolls [300.0]
                            :markers [{:start_position 600.0}]})]
      (is (tool-error? resp)))))

(deftest test-prepare-with-midrolls-duplicate
  (testing "prepare_episode_version with duplicate midroll timestamps returns error"
    (let [resp (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                           {:episode_id "ep-001"
                            :midrolls [300.0 300.0]})]
      (is (tool-error? resp)))))

(deftest test-prepare-with-midrolls-negative
  (testing "prepare_episode_version with negative midroll timestamp returns error"
    (let [resp (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                           {:episode_id "ep-001"
                            :midrolls [-1]})]
      (is (tool-error? resp)))))

(deftest test-prepare-with-midrolls-unsorted
  (testing "prepare_episode_version with unsorted midroll timestamps returns error"
    (let [resp (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                           {:episode_id "ep-001"
                            :midrolls [900.0 300.0]})]
      (is (tool-error? resp)))))

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
                          {:headers {"Content-Type" "application/json"
                                     "mCP-sESSion-iD" sid}
                           :body (json/generate-string
                                  {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})})]
      (is (= 200 (:status resp)))
      (is (not (str/includes? (:body resp) "Invalid or missing Mcp-Session-Id")))))

  (testing "Malformed JSON body returns 400"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"
                                     "Mcp-Session-Id" *session-id*}
                           :body "{invalid json}"
                           :throw false})]
      (is (= 400 (:status resp)))
      (is (str/includes? (:body resp) "Invalid JSON"))))

  (testing "Missing session ID returns 400"
    (let [resp (http/post *mcp-url*
                          {:headers {"Content-Type" "application/json"}
                           :body (json/generate-string
                                  {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})
                           :throw false})]
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

;; ─── Tests: Filter Pass-Through (ids[], sort, itunes_type, include) ───────────

(deftest test-list-episodes-passes-ids
  (testing "list_episodes passes ids[] to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_episodes"
                             {:series_id "s-001"
                              :ids ["ep-001" "ep-002"]}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=ep-001"))
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=ep-002")))))

(deftest test-list-episodes-passes-itunes-type
  (testing "list_episodes passes itunes_type to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_episodes"
                             {:series_id "s-001"
                              :itunes_type "bonus"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "itunes_type=bonus")))))

(deftest test-list-series-passes-ids-and-sort
  (testing "list_series passes ids[] and sort to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_series"
                             {:ids ["s-001"] :sort "title"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=s-001"))
      (is (str/includes? (or (:query-string req) "") "sort=title")))))

(deftest test-list-seasons-passes-ids-and-sort
  (testing "list_seasons passes ids[] and sort to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_seasons"
                             {:series_id "s-001"
                              :ids ["sn-001"] :sort "created_at"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=sn-001"))
      (is (str/includes? (or (:query-string req) "") "sort=created_at")))))

(deftest test-list-credits-passes-ids-and-sort
  (testing "list_credits passes ids[] and sort to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_credits"
                             {:episode_id "ep-001"
                              :ids ["cr-001"] :sort "position"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=cr-001"))
      (is (str/includes? (or (:query-string req) "") "sort=position")))))

(deftest test-search-people-passes-ids-and-sort
  (testing "search_people passes ids[] and sort to API"
    (tool-result (tool-call! *mcp-url* *session-id* "search_people"
                             {:q "Chris" :ids ["p-001"] :sort "last_name"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=p-001"))
      (is (str/includes? (or (:query-string req) "") "sort=last_name")))))

(deftest test-list-episode-versions-passes-ids
  (testing "list_episode_versions passes ids[] to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_episode_versions"
                             {:episode_id "ep-001"
                              :ids ["v-001"]}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=v-001")))))

(deftest test-list-marker-points-passes-ids
  (testing "list_marker_points passes ids[] to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_marker_points"
                             {:episode_version_id "v-001"
                              :ids ["mp-001"]}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=mp-001")))))

(deftest test-list-marker-point-content-rules-passes-ids-and-sort
  (testing "list_marker_point_content_rules passes ids[] and sort to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_marker_point_content_rules"
                             {:marker_point_id "mp-001"
                              :ids ["cr-rule-001"] :sort "priority"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "ids%5B%5D=cr-rule-001"))
      (is (str/includes? (or (:query-string req) "") "sort=priority")))))

(deftest test-list-feed-items-passes-feed-id-as-array
  (testing "list_feed_items passes feed_id as feed_id[] (array)"
    (tool-result (tool-call! *mcp-url* *session-id* "list_feed_items"
                             {:feed_id ["f-001" "f-002"]}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "feed_id%5B%5D=f-001"))
      (is (str/includes? (or (:query-string req) "") "feed_id%5B%5D=f-002")))))

(deftest test-get-season-passes-include
  (testing "get_season passes include param to API"
    (tool-result (tool-call! *mcp-url* *session-id* "get_season"
                             {:season_id "sn-001" :include "series"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "include=series")))))

(deftest test-list-episodes-passes-sort
  (testing "list_episodes passes sort to API"
    (tool-result (tool-call! *mcp-url* *session-id* "list_episodes"
                             {:series_id "s-001" :sort "-released_at"}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "sort=-released_at")))))

(deftest test-list-episodes-passes-published-as-boolean
  (testing "list_episodes passes published as boolean, not string"
    (tool-result (tool-call! *mcp-url* *session-id* "list_episodes"
                             {:series_id "s-001" :published true}))
    (let [req (last @(:received-requests *fake-api*))]
      (is (str/includes? (or (:query-string req) "") "published=true")))))

;; ─── Critical: fetch-all-pages pagination ────────────────────────────────────

(deftest test-fetch-all-pages-multi-page
  (testing "fetch-all-pages iterates through all pages"
    (reset! pagination-config {:total 3 :per-page 1})
    (try
      (let [result (art19-mcp/fetch-all-pages "/paginated_resource" {} {})]
        (is (= 3 (count (:items result))))
        (is (= "pr-0" (get-in result [:items 0 :id])))
        (is (= "pr-2" (get-in result [:items 2 :id]))))
      (finally (reset! pagination-config nil)))))

(deftest test-fetch-all-pages-single-page
  (testing "fetch-all-pages returns items when all fit on one page"
    (reset! pagination-config {:total 2 :per-page 10})
    (try
      (let [result (art19-mcp/fetch-all-pages "/paginated_resource" {} {})]
        (is (= 2 (count (:items result)))))
      (finally (reset! pagination-config nil)))))

(deftest test-fetch-all-pages-empty
  (testing "fetch-all-pages returns empty when API returns no data"
    (reset! pagination-config {:total 0 :per-page 1})
    (try
      (let [result (art19-mcp/fetch-all-pages "/paginated_resource" {} {})]
        (is (= [] (:items result))))
      (finally (reset! pagination-config nil)))))

(deftest test-fetch-all-pages-api-error-mid-pagination
  (testing "fetch-all-pages returns error when API fails mid-pagination"
    ;; Use a non-existent path to trigger a 404 on page 2
    ;; Page 1 succeeds (returns data with :next), page 2 returns 404
    ;; We simulate this by using a path that returns 404
    (let [result (art19-mcp/fetch-all-pages "/this-endpoint-does-not-exist" {} {})]
      (is (:error result)))))

;; ─── Critical: resolve-series-id edge cases ──────────────────────────────────

(deftest test-resolve-series-id-uuid-passthrough
  (testing "resolve-series-id passes UUID directly without API call"
    (let [uuid "550e8400-e29b-41d4-a716-446655440000"
          result (art19-mcp/resolve-series-id uuid {})]
      (is (= uuid (:id result)))
      (is (nil? (:error result))))))

(deftest test-resolve-series-id-not-found
  (testing "resolve-series-id returns error when series not found"
    (let [result (art19-mcp/resolve-series-id "nonexistent-slug" {})]
      (is (:error result))
      (is (str/includes? (:error result) "nonexistent-slug")))))

;; ─── Critical: update_episode_version validation ─────────────────────────────

(deftest test-update-version-invalid-processing-status
  (testing "update_episode_version rejects invalid processing_status"
    (let [resp (tool-call! *mcp-url* *session-id* "update_episode_version"
                           {:version_id "v-001" :processing_status "bogus"})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "processing_status")))))

(deftest test-update-version-invalid-status-on-completion
  (testing "update_episode_version rejects invalid status_on_completion"
    (let [resp (tool-call! *mcp-url* *session-id* "update_episode_version"
                           {:version_id "v-001" :status_on_completion "bogus"})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "status_on_completion")))))

;; ─── Critical: prepare_episode_version validation ────────────────────────────

(deftest test-prepare-version-invalid-status-on-completion
  (testing "prepare_episode_version rejects invalid status_on_completion"
    (let [resp (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                           {:episode_id "ep-001" :status_on_completion "bogus"})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "status_on_completion")))))

(deftest test-prepare-version-missing-episode-id
  (testing "prepare_episode_version requires episode_id"
    (let [resp (tool-call! *mcp-url* *session-id* "prepare_episode_version"
                           {:status_on_completion "active"})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "episode_id")))))

(deftest test-wait-for-processing
  (testing "wait_for_processing polls until version is active"
    (reset! version-transition {:polls-before-active 2})
    (reset! version-poll-counts {})
    (try
      (let [resp (tool-call! *mcp-url* *session-id* "wait_for_processing"
                             {:version_id "v-003" :timeout_seconds 10 :poll_interval_seconds 1})
            result (tool-result resp)]

        (is (not (tool-error? resp)) "wait_for_processing should succeed")
        (is (some? result) "should return version data")
        (is (= "v-003" (:id result)) "should return correct version")
        (is (= "active" (get-in result [:attributes :processing_status]))))
      (finally
        (reset! version-transition nil)
        (reset! version-poll-counts {})))))

(deftest test-wait-for-processing-timeout
  (testing "wait_for_processing times out for non-terminal version"
    (let [resp (tool-call! *mcp-url* *session-id* "wait_for_processing"
                           {:version_id "v-003" :timeout_seconds 1 :poll_interval_seconds 1})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "Timeout")))))

(deftest test-wait-for-processing-missing-version
  (testing "wait_for_processing rejects missing version_id"
    (let [resp (tool-call! *mcp-url* *session-id* "wait_for_processing" {})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "version_id")))))

;; ─── Critical: list_episodes missing-args validation ─────────────────────────

(deftest test-list-episodes-requires-series-or-season
  (testing "list_episodes returns error when no series_id, series_slug, or season_id"
    (let [resp (tool-call! *mcp-url* *session-id* "list_episodes" {})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "series_id")))))

(deftest test-list-episodes-invalid-slug
  (testing "list_episodes returns error for non-existent series slug"
    (let [resp (tool-call! *mcp-url* *session-id* "list_episodes"
                           {:series_slug "totally-fake-series"})]
      (is (tool-error? resp))
      (is (str/includes?
           (get-in resp [:result :content 0 :text])
           "not found")))))

;; ─── Deferred improvements (from expert review) ─────────────────────────────
;; TODO: Error-path tests — add tests for API 4xx/5xx responses on CRUD ops
;;   (create_episode, update_episode, delete_episode, create_credit, etc.)
;;   Currently the fake API always returns success; add a "fail mode" flag.
;; TODO: search-people with nil/empty q — validate schema rejects it
;; TODO: list_media_assets returns raw vector while other list tools return
;;   {:key [...]}. Normalize response shape for consistency.
;; TODO: Session TTL/cleanup — sessions atom grows forever. Add TTL-based
;;   expiry or a max-sessions cap.
;; TODO: create_episode_version with copy_active_version + source_url —
;;   schema says "cannot be combined" but no validation enforces it.
;; TODO: find-header direct unit test — the three-way fallback (string key
;;   → keyword key → case-insensitive scan) is only tested indirectly.

;; ─── Runner ─────────────────────────────────────────────────────────────────

(defn -main [& _args]
  (println "\nart19-mcp integration tests")
  (println "===========================")
  (let [{:keys [pass fail error]} (run-tests *ns*)]
    (println (str "\nResults: " pass " passed, " fail " failed, " error " errors"))
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
