#!/usr/bin/env bb
;; art19_mcp.bb — ART19 MCP Server (Streamable HTTP, 2025-03-26)
;;
;; Serves the ART19 Content API as an MCP server.
;; Transport: Streamable HTTP (single /mcp POST endpoint)
;; Compatible with mcp-injector {:art19 {:url "http://localhost:PORT/mcp"}}
;;
;; Auth: ART19_API_TOKEN + ART19_API_CREDENTIAL env vars
;;        or ~/.config/art19/config.edn {:api-token "..." :api-credential "..."}

(ns art19-mcp
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ─── Configuration ──────────────────────────────────────────────────────────

(def base-url "https://art19.com")
(def protocol-version "2025-03-26")
(def server-info {:name "art19-mcp" :version "1.0.0"})

(defn load-config-file []
  (let [path (str (System/getProperty "user.home") "/.config/art19/config.edn")]
    (when (.exists (java.io.File. path))
      (edn/read-string (slurp path)))))

(defn get-art19-config []
  (let [file-cfg (load-config-file)]
    {:api-token (or (System/getenv "ART19_API_TOKEN") (:api-token file-cfg))
     :api-credential (or (System/getenv "ART19_API_CREDENTIAL") (:api-credential file-cfg))}))

(defn auth-header [{:keys [api-token api-credential]}]
  (str "Token token=\"" api-token "\", credential=\"" api-credential "\""))

(defn log [level message data]
  (let [output (json/generate-string
                {:timestamp (str (java.time.Instant/now))
                 :level level
                 :message message
                 :data data})]
    (if (contains? #{"error" "warn"} level)
      (binding [*out* *err*] (println output))
      (println output))))

;; ─── Session Management ─────────────────────────────────────────────────────

(def sessions (atom {})) ;; session-id -> {:created-at ...}

(defn new-session-id []
  (str (java.util.UUID/randomUUID)))

(defn create-session! []
  (let [sid (new-session-id)]
    (swap! sessions assoc sid {:created-at (System/currentTimeMillis)})
    sid))

(defn valid-session? [sid]
  (boolean (and sid (contains? @sessions sid))))

(defn find-header [request header-name]
  (let [headers (:headers request)
        low-name (str/lower-case header-name)]
    (or (get headers low-name)
        (get headers (keyword low-name))
        (some (fn [[k v]] (when (= low-name (str/lower-case (name k))) v)) headers))))

;; ─── ART19 HTTP Client ──────────────────────────────────────────────────────

(defn api-request! [method path query-params body config]
  (let [url (str base-url path)
        headers {"Accept" "application/vnd.api+json"
                 "Content-Type" "application/vnd.api+json"
                 "Authorization" (auth-header config)}
        opts (cond-> {:method method
                      :uri url
                      :headers headers
                      :throw false}
               (seq query-params) (assoc :query-params query-params)
               body (assoc :body (json/generate-string body)))]
    (log "debug" "API Request" {:method method :path path :query-params query-params})
    (try
      (let [start (System/currentTimeMillis)
            resp (http-client/request opts)
            elapsed (- (System/currentTimeMillis) start)
            status (:status resp)
            resp-body (:body resp)
            body (when-not (str/blank? resp-body)
                   (try (json/parse-string resp-body true)
                        (catch Exception _ {:raw resp-body})))]
        (log "debug" "API Response" {:method method :path path :status status :elapsed_ms elapsed})
        (if (>= status 400)
          (let [err-obj (first (:errors body))
                code (get err-obj :code)
                detail (get err-obj :detail)
                title (get err-obj :title)
                source (get err-obj :source)
                param (get source :parameter)
                msg-parts (cond-> []
                            code (conj (str "code=" code))
                            param (conj (str "param=" param))
                            detail (conj detail))]
            {:error true
             :status status
             :message (str title ": " (str/join " | " msg-parts))
             :errors body})
          {:data body :status status :uri (:uri resp)}))
      (catch Exception e
        (log "error" "API Exception" {:method method :path path :error (.getMessage e)})
        {:error true :message (.getMessage e)}))))

(defn api-get
  ([path config] (api-get path {} config))
  ([path params config] (api-request! :get path params nil config)))

(defn api-post [path body config]
  (api-request! :post path {} body config))

(defn api-patch [path body config]
  (api-request! :patch path {} body config))

(defn api-delete [path config]
  (api-request! :delete path {} nil config))

(defn fetch-all-pages [path query-params config & {:keys [max-pages] :or {max-pages 20}}]
  (loop [page 1 acc []]
    (let [params (merge query-params
                        {"page[number]" (str page)
                         "page[size]" "100"})
          resp (api-get path params config)
          data (get-in resp [:data :data])
          items (into acc (or data []))
          next-link (get-in resp [:data :links :next])]
      (if (and next-link (seq data) (< page max-pages))
        (recur (inc page) items)
        (if (:error resp)
          resp
          {:items items})))))

;; ─── Tool Implementations ───────────────────────────────────────────────────

(defn resolve-series-slug [s]
  (get {"lu" "linux-unplugged"
        "linux-unplugged" "linux-unplugged"
        "twib" "this-week-in-bitcoin"
        "this-week-in-bitcoin" "this-week-in-bitcoin"
        "tl" "the-launch"
        "the-launch" "the-launch"
        "cr" "coder-radio"
        "coder-radio" "coder-radio"
        "sh" "self-hosted"
        "self-hosted" "self-hosted"}
       (str/lower-case s) s))

(defn resolve-series-id [slug-or-id config]
  (if (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" slug-or-id)
    {:id slug-or-id}
    (let [slug (resolve-series-slug slug-or-id)
          resp (api-get "/series" {"q" slug "page[number]" "1" "page[size]" "5"} config)
          data (get-in resp [:data :data])]
      (if (seq data)
        {:id (:id (first data))}
        {:error (str "Series not found: " slug-or-id)}))))

;; EPISODES

(defn tool-list-episodes [{:keys [ids series_id series_slug season_id published q
                                   sort year month released_after released_before
                                   itunes_type page page_size]}
                          config]
  (let [sid-result (if series_slug
                     (resolve-series-id series_slug config)
                     {:id series_id})
        _ (when (:error sid-result) (throw (ex-info (:error sid-result) {})))
        _ (when-not (or (:id sid-result) season_id)
            (throw (ex-info "One of series_id, series_slug, or season_id is required" {:type :bad-request})))
        params (cond-> {}
                 (seq ids) (assoc "ids[]" ids)
                 (:id sid-result) (assoc "series_id" (:id sid-result))
                 season_id (assoc "season_id" season_id)
                 (some? published) (assoc "published" published)
                 q (assoc "q" q)
                 sort (assoc "sort" sort)
                 year (assoc "year" (str year))
                 month (assoc "month" (str month))
                 released_after (assoc "released_after" released_after)
                 released_before (assoc "released_before" released_before)
                 itunes_type (assoc "itunes_type" itunes_type)
                 true (assoc "page[number]" (str (or page 1)))
                 true (assoc "page[size]" (str (or page_size 100))))
        resp (api-get "/episodes" params config)]
    (if (:error resp)
      resp
      {:episodes (mapv (fn [ep]
                         {:id (:id ep)
                          :title (get-in ep [:attributes :title])
                          :status (get-in ep [:attributes :status])
                          :published (get-in ep [:attributes :published])
                          :released_at (get-in ep [:attributes :released_at])
                          :duration (get-in ep [:attributes :duration])})
                       (get-in resp [:data :data]))
       :total (get-in resp [:data :meta :total])
       :page page
       :links (get-in resp [:data :links])})))

(defn tool-get-episode [{:keys [episode_id include]} config]
  (let [params (if include {"include" include} {})
        resp (api-get (str "/episodes/" episode_id) params config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-create-episode [{:keys [series_id series_slug title description description_is_html
                                   season_id itunes_type premium_status
                                   released_at release_end_at release_immediately]}
                           config]
  (when (str/blank? title) (throw (ex-info "title is required" {:type :bad-request})))
  (let [sid-result (cond
                     series_slug (resolve-series-id series_slug config)
                     series_id {:id series_id}
                     :else {:error "series_id or series_slug required"})
        _ (when (:error sid-result) (throw (ex-info (:error sid-result) {})))
        attrs (cond-> {:title title}
                description (assoc :description description)
                description_is_html (assoc :description_is_html description_is_html)
                itunes_type (assoc :itunes_type itunes_type)
                premium_status (assoc :premium_status premium_status)
                released_at (assoc :released_at released_at)
                release_end_at (assoc :release_end_at release_end_at)
                (some? release_immediately) (assoc :release_immediately release_immediately))
        rels (cond-> {:series {:data {:type "series" :id (:id sid-result)}}}
               season_id (assoc :season {:data {:type "seasons" :id season_id}}))
        body {:data {:type "episodes" :attributes attrs :relationships rels}}
        resp (api-post "/episodes" body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-update-episode [{:keys [episode_id title description description_is_html
                                   published released_at release_end_at release_immediately
                                   itunes_type premium_status cover_image_id
                                   allow_user_comments]}
                           config]
  (let [attrs (cond-> {}
                title (assoc :title title)
                description (assoc :description description)
                description_is_html (assoc :description_is_html description_is_html)
                (some? published) (assoc :published published)
                released_at (assoc :released_at released_at)
                release_end_at (assoc :release_end_at release_end_at)
                (some? release_immediately) (assoc :release_immediately release_immediately)
                itunes_type (assoc :itunes_type itunes_type)
                premium_status (assoc :premium_status premium_status)
                cover_image_id (assoc :cover_image_id cover_image_id)
                (some? allow_user_comments) (assoc :allow_user_comments allow_user_comments))
        body {:data {:type "episodes" :id episode_id :attributes attrs}}
        resp (api-patch (str "/episodes/" episode_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-episode [{:keys [episode_id]} config]
  (let [resp (api-delete (str "/episodes/" episode_id) config)]
    (if (:error resp)
      resp
      {:deleted episode_id})))

(defn tool-publish-episode [{:keys [episode_id released_at release_immediately]} config]
  (let [attrs (cond-> {:published true}
                released_at (assoc :released_at released_at)
                (some? release_immediately) (assoc :release_immediately release_immediately))
        body {:data {:type "episodes" :id episode_id :attributes attrs}}
        resp (api-patch (str "/episodes/" episode_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

;; SERIES

(defn tool-list-series [{:keys [ids q sort]} config]
  (let [params (cond-> {}
                 (seq ids) (assoc "ids[]" ids)
                 q (assoc "q" q)
                 sort (assoc "sort" sort))
        resp (fetch-all-pages "/series" params config)]
    (if (:error resp)
      resp
      {:series (mapv (fn [s]
                       {:id (:id s)
                        :title (get-in s [:attributes :title])
                        :slug (get-in s [:attributes :slug])
                        :status (get-in s [:attributes :status])})
                     (:items resp))})))

(defn tool-get-series [{:keys [series_id series_slug include]} config]
  (let [sid-result (if series_slug
                     (resolve-series-id series_slug config)
                     {:id series_id})
        _ (when (:error sid-result) (throw (ex-info (:error sid-result) {})))
        params (if include {"include" include} {})
        resp (api-get (str "/series/" (:id sid-result)) params config)]
    (if (:error resp)
      resp
      (:data resp))))

;; SEASONS

(defn tool-list-seasons [{:keys [ids series_id series_slug q sort]} config]
  (let [sid-result (if series_slug
                     (resolve-series-id series_slug config)
                     {:id series_id})
        _ (when (:error sid-result) (throw (ex-info (:error sid-result) {})))
        resp (fetch-all-pages "/seasons" (cond-> {"series_id" (:id sid-result)}
                                           (seq ids) (assoc "ids[]" ids)
                                           q (assoc "q" q)
                                           sort (assoc "sort" sort))
                              config)]
    (if (:error resp)
      resp
      {:seasons (mapv (fn [sn]
                        {:id (:id sn)
                         :title (get-in sn [:attributes :title])
                         :number (get-in sn [:attributes :number])})
                      (:items resp))})))

(defn tool-get-season [{:keys [season_id include]} config]
  (let [params (if include {"include" include} {})
        resp (api-get (str "/seasons/" season_id) params config)]
    (if (:error resp)
      resp
      (:data resp))))

;; CREDITS

(defn tool-list-credits [{:keys [ids episode_id sort]} config]
  (let [resp (fetch-all-pages "/credits"
                              (cond-> {"creditable_id" episode_id
                                       "creditable_type" "Episode"}
                                (seq ids) (assoc "ids[]" ids)
                                sort (assoc "sort" sort))
                              config)]
    (if (:error resp)
      resp
      {:credits (mapv (fn [cr]
                        {:id (:id cr)
                         :role (get-in cr [:attributes :type])
                         :person_id (get-in cr [:relationships :person :data :id])})
                      (:items resp))})))

(defn tool-add-credit [{:keys [episode_id person_id role]} config]
  (let [body {:data {:type "credits"
                     :attributes {:type role}
                     :relationships {:creditable {:data {:type "episodes" :id episode_id}}
                                     :person {:data {:type "people" :id person_id}}}}}
        resp (api-post "/credits" body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-update-credit [{:keys [credit_id role]} config]
  (let [body {:data {:type "credits" :id credit_id :attributes {:type role}}}
        resp (api-patch (str "/credits/" credit_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-remove-credit [{:keys [credit_id]} config]
  (let [resp (api-delete (str "/credits/" credit_id) config)]
    (if (:error resp)
      resp
      {:removed credit_id})))

;; PEOPLE

(defn tool-search-people [{:keys [ids q sort page page_size]} config]
  (let [params (cond-> {"q" q}
                 (seq ids) (assoc "ids[]" ids)
                 sort (assoc "sort" sort)
                 page (assoc "page[number]" (str page))
                 page_size (assoc "page[size]" (str page_size)))
        resp (api-get "/people" params config)]
    (if (:error resp)
      resp
      {:people (mapv (fn [p]
                       {:id (:id p)
                        :full_name (get-in p [:attributes :full_name])
                        :first_name (get-in p [:attributes :first_name])
                        :last_name (get-in p [:attributes :last_name])})
                     (get-in resp [:data :data]))})))

(defn tool-get-person [{:keys [person_id]} config]
  (let [resp (api-get (str "/people/" person_id) {} config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-create-person [{:keys [first_name last_name email bio
                                    born died from_country from_locality from_region
                                    private_email]}
                           config]
  (let [attrs (cond-> {:first_name first_name :last_name last_name}
                email (assoc :public_email email)
                bio (assoc :biography bio)
                born (assoc :born born)
                died (assoc :died died)
                from_country (assoc :from_country from_country)
                from_locality (assoc :from_locality from_locality)
                from_region (assoc :from_region from_region)
                (some? private_email) (assoc :private_email private_email))
        body {:data {:type "people" :attributes attrs}}
        resp (api-post "/people" body config)]
    (if (:error resp)
      resp
      (:data resp))))

;; EPISODE VERSIONS

(defn tool-list-versions [{:keys [ids episode_id feed_item_id]} config]
  (let [params (cond-> {}
                 (seq ids) (assoc "ids[]" ids)
                 episode_id (assoc "episode_id" episode_id)
                 feed_item_id (assoc "feed_item_id" feed_item_id))
        resp (fetch-all-pages "/episode_versions" params config)]
    (if (:error resp)
      resp
      {:versions (mapv (fn [v]
                          {:id (:id v)
                           :processing_status (get-in v [:attributes :processing_status])
                           :status_on_completion (get-in v [:attributes :status_on_completion])
                           :source_url (get-in v [:attributes :source_url])
                           :ad_insertion_points_count (get-in v [:attributes :ad_insertion_points_count])
                           :validation_errors (get-in v [:attributes :validation_errors])
                           :created_at (get-in v [:attributes :created_at])
                           :updated_at (get-in v [:attributes :updated_at])})
                        (:items resp))})))

(defn tool-create-version [{:keys [episode_id source_url status_on_completion
                                   copy_active_version copy_marker_points]} config]
  (let [attrs (cond-> {:source_url source_url}
                status_on_completion (assoc :status_on_completion status_on_completion)
                (some? copy_active_version) (assoc :copy_active_version copy_active_version)
                (some? copy_marker_points) (assoc :copy_marker_points copy_marker_points))
        body {:data {:type "episode_versions"
                     :attributes attrs
                     :relationships {:episode {:data {:type "episodes" :id episode_id}}}}}
        resp (api-post "/episode_versions" body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-get-version [{:keys [version_id]} config]
  (let [resp (api-get (str "/episode_versions/" version_id) {} config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-version [{:keys [version_id]} config]
  (let [resp (api-delete (str "/episode_versions/" version_id) config)]
    (if (:error resp)
      resp
      {:deleted version_id})))

(defn tool-update-version [{:keys [version_id processing_status source_url status_on_completion
                                   copy_active_version copy_marker_points]} config]
  (when (and processing_status
             (not (#{"submitted" "inactive"} processing_status)))
    (throw (ex-info "processing_status must be 'submitted' or 'inactive'" {:type :bad-request})))
  (when (and status_on_completion
             (not (#{"active" "inactive"} status_on_completion)))
    (throw (ex-info "status_on_completion must be 'active' or 'inactive'" {:type :bad-request})))
  (let [attrs (cond-> {}
                processing_status (assoc :processing_status processing_status)
                source_url (assoc :source_url source_url)
                status_on_completion (assoc :status_on_completion status_on_completion)
                (some? copy_active_version) (assoc :copy_active_version copy_active_version)
                (some? copy_marker_points) (assoc :copy_marker_points copy_marker_points))
        body {:data {:type "episode_versions" :id version_id :attributes attrs}}
        resp (api-patch (str "/episode_versions/" version_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

;; EPISODE SIBLINGS

(defn tool-get-next-sibling [{:keys [episode_id rss]} config]
  (let [params (when rss {"rss" (str rss)})
        resp (api-get (str "/episodes/" episode_id "/next_sibling") params config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-get-previous-sibling [{:keys [episode_id rss]} config]
  (let [params (when rss {"rss" (str rss)})
        resp (api-get (str "/episodes/" episode_id "/previous_sibling") params config)]
    (if (:error resp)
      resp
      (:data resp))))

;; IMAGES

(defn tool-upload-image [{:keys [source_url crop_data series_id]} config]
  (when (str/blank? source_url) (throw (ex-info "source_url is required" {:type :bad-request})))
  (when (str/blank? series_id) (throw (ex-info "series_id is required" {:type :bad-request})))
  (let [attrs (cond-> {:source_url source_url}
                crop_data (assoc :crop_data crop_data))
        body {:data {:type "images"
                     :attributes attrs
                     :relationships {:bucket {:data {:type "series" :id series_id}}}}}
        resp (api-post "/images" body config)]
    (if (:error resp)
      resp
      (:data resp))))

;; MEDIA ASSETS (audio file details: duration, file_size, cdn_url)

(defn tool-list-media-assets [{:keys [attachment_id attachment_type]} config]
  (let [params (cond-> {}
                 attachment_id (assoc "attachment_id" attachment_id)
                 attachment_type (assoc "attachment_type" attachment_type))
        resp (api-get "/media_assets" params config)]
    (if (:error resp)
      resp
      (get-in resp [:data :data]))))

;; MARKER POINTS (chapter markers / ad insertion)

(defn tool-list-marker-points [{:keys [ids episode_version_id episode_id series_id season_id type]} config]
  (let [params (cond-> {}
                 (seq ids) (assoc "ids[]" ids)
                 episode_version_id (assoc "episode_version_id" episode_version_id)
                 episode_id (assoc "episode_id" episode_id)
                 series_id (assoc "series_id" series_id)
                 season_id (assoc "season_id" season_id)
                 type (assoc "type" type))
        resp (fetch-all-pages "/marker_points" params config)]
    (if (:error resp)
      resp
      {:marker_points (mapv (fn [mp]
                              {:id (:id mp)
                               :position_type (get-in mp [:attributes :position_type])
                               :position_type_name (get-in mp [:attributes :position_type_name])
                               :start_position (get-in mp [:attributes :start_position])
                               :maximum_content_count (get-in mp [:attributes :maximum_content_count])
                               :maximum_content_duration (get-in mp [:attributes :maximum_content_duration])
                               :type (get-in mp [:attributes :type])
                               :default_for (get-in mp [:attributes :default_for])
                               :created_at (get-in mp [:attributes :created_at])
                               :updated_at (get-in mp [:attributes :updated_at])})
                            (:items resp))})))

(defn tool-create-marker-point [{:keys [episode_version_id position_type start_position type
                                        maximum_content_duration maximum_content_count]}
                                config]
  (let [attrs (cond-> {:position_type position_type}
                (some? start_position) (assoc :start_position start_position)
                type (assoc :type type)
                maximum_content_duration (assoc :maximum_content_duration maximum_content_duration)
                maximum_content_count (assoc :maximum_content_count maximum_content_count))
        body {:data {:type "marker_points"
                     :attributes attrs
                     :relationships {:episode_version {:data {:type "episode_versions"
                                                              :id episode_version_id}}}}}
        resp (api-post "/marker_points" body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-marker-point [{:keys [marker_point_id]} config]
  (let [resp (api-delete (str "/marker_points/" marker_point_id) config)]
    (if (:error resp)
      resp
      {:deleted marker_point_id})))

(defn tool-get-marker-point [{:keys [marker_point_id]} config]
  (let [resp (api-get (str "/marker_points/" marker_point_id) {} config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-update-marker-point [{:keys [marker_point_id position_type start_position
                                        maximum_content_duration maximum_content_count]}
                                config]
  (let [attrs (cond-> {}
                position_type (assoc :position_type position_type)
                (some? start_position) (assoc :start_position start_position)
                maximum_content_duration (assoc :maximum_content_duration maximum_content_duration)
                maximum_content_count (assoc :maximum_content_count maximum_content_count))
        body {:data {:type "marker_points" :id marker_point_id :attributes attrs}}
        resp (api-patch (str "/marker_points/" marker_point_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

;; MARKER POINT CONTENT RULES (ad targeting)

(defn tool-list-marker-point-content-rules [{:keys [ids marker_point_id sort]} config]
  (let [params (cond-> {}
                 (seq ids) (assoc "ids[]" ids)
                 marker_point_id (assoc "marker_point_id" marker_point_id)
                 sort (assoc "sort" sort))
        resp (fetch-all-pages "/marker_point_content_rules" params config)]
    (if (:error resp)
      resp
      {:content_rules (mapv (fn [cr]
                              {:id (:id cr)
                               :priority (get-in cr [:attributes :priority])
                               :content_type (get-in cr [:attributes :content_type])
                               :start_at (get-in cr [:attributes :start_at])
                               :end_at (get-in cr [:attributes :end_at])
                               :created_at (get-in cr [:attributes :created_at])
                               :updated_at (get-in cr [:attributes :updated_at])})
                            (:items resp))})))

(defn tool-create-marker-point-content-rule [{:keys [marker_point_id priority content_type
                                                    start_at end_at content_id content_type_target]}
                                            config]
  (when-not priority (throw (ex-info "priority is required" {:type :bad-request})))
  (let [attrs (cond-> {:priority priority}
                content_type (assoc :content_type content_type)
                start_at (assoc :start_at start_at)
                end_at (assoc :end_at end_at))
        rels (cond-> {:marker_point {:data {:type "marker_points" :id marker_point_id}}}
               (and content_id content_type_target)
               (assoc :content {:data {:type content_type_target :id content_id}}))
        body {:data {:type "marker_point_content_rules"
                     :attributes attrs
                     :relationships rels}}
        resp (api-post "/marker_point_content_rules" body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-update-marker-point-content-rule [{:keys [content_rule_id priority content_type
                                                    start_at end_at content_id content_type_target]}
                                            config]
  (let [attrs (cond-> {}
                priority (assoc :priority priority)
                content_type (assoc :content_type content_type)
                start_at (assoc :start_at start_at)
                end_at (assoc :end_at end_at))
        body (cond-> {:data {:type "marker_point_content_rules" :id content_rule_id :attributes attrs}}
               (and content_id content_type_target)
               (assoc-in [:data :relationships :content] {:data {:type content_type_target :id content_id}}))
        resp (api-patch (str "/marker_point_content_rules/" content_rule_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-marker-point-content-rule [{:keys [content_rule_id]} config]
  (let [resp (api-delete (str "/marker_point_content_rules/" content_rule_id) config)]
    (if (:error resp)
      resp
      {:deleted content_rule_id})))

;; COMPOUND TOOL — prepare_episode_version

(defn tool-prepare-episode-version
  "Create a new episode version, add markers with content rules, and submit
   for processing. The version is created by copying audio from the currently
   active version. Returns the new version's status — agent polls
   get_episode_version until processing_status is 'active' or 'processing_failed'."
  [{:keys [episode_id markers status_on_completion released_at]} config]
  (when (str/blank? episode_id)
    (throw (ex-info "episode_id is required" {:type :bad-request})))
  (let [soc (or status_on_completion "active")
        _ (when (not (#{"active" "inactive"} soc))
            (throw (ex-info "status_on_completion must be 'active' or 'inactive'" {:type :bad-request})))
        create-attrs (cond-> {:copy_active_version true
                              :copy_marker_points true}
                       released_at (assoc :released_at released_at))
        create-body {:data {:type "episode_versions"
                            :attributes create-attrs
                            :relationships {:episode {:data {:type "episodes" :id episode_id}}}}}
        create-resp (api-post "/episode_versions" create-body config)]
    (if (:error create-resp)
      create-resp
      (let [version-id (get-in create-resp [:data :data :id])
            marker-results (when (seq markers)
                             (mapv
                              (fn [marker]
                                (let [pos-type (or (:position_type marker) 1)
                                      mp-attrs (cond-> {:position_type pos-type
                                                        :type (or (:type marker) "AdInsertionPoint")
                                                        :maximum_content_count (or (:maximum_content_count marker) 2)
                                                        :maximum_content_duration (or (:maximum_content_duration marker) 120)}
                                                 (:start_position marker) (assoc :start_position (:start_position marker)))
                                      mp-body {:data {:type "marker_points"
                                                     :attributes mp-attrs
                                                     :relationships {:episode_version {:data {:type "episode_versions"
                                                                                              :id version-id}}}}}
                                      mp-resp (api-post "/marker_points" mp-body config)]
                                  (if (:error mp-resp)
                                    {:error true :marker marker :message (:message mp-resp)}
                                    (let [mp-id (get-in mp-resp [:data :data :id])
                                          cr-priority (or (:priority marker) 1)
                                          cr-attrs (cond-> {:priority cr-priority}
                                                     (:content_type marker) (assoc :content_type (:content_type marker))
                                                     (:start_at marker) (assoc :start_at (:start_at marker))
                                                     (:end_at marker) (assoc :end_at (:end_at marker)))
                                          cr-rels (cond-> {:marker_point {:data {:type "marker_points" :id mp-id}}}
                                                    (and (:content_id marker) (:content_type_target marker))
                                                    (assoc :content {:data {:type (:content_type_target marker)
                                                                            :id (:content_id marker)}}))
                                          cr-body {:data {:type "marker_point_content_rules"
                                                          :attributes cr-attrs
                                                          :relationships cr-rels}}
                                          cr-resp (api-post "/marker_point_content_rules" cr-body config)]
                                      (if (:error cr-resp)
                                        {:error true :marker marker :message (:message cr-resp)}
                                        {:marker_id mp-id :content_rule_id (get-in cr-resp [:data :data :id])})))))
                              markers))
            submit-attrs {:processing_status "submitted"
                          :status_on_completion soc}
            submit-body {:data {:type "episode_versions"
                                :id version-id
                                :attributes submit-attrs}}
            submit-resp (api-patch (str "/episode_versions/" version-id) submit-body config)]
        (if (:error submit-resp)
          {:error true
           :message (str "Version created (" version-id ") but submit failed: " (:message submit-resp))
           :version_id version-id}
          (let [failed-markers (filterv :error (or marker-results []))
                succeeded-markers (filterv #(not (:error %)) (or marker-results []))]
            {:version_id version-id
             :processing_status "submitted"
             :status_on_completion soc
             :markers_added (count succeeded-markers)
             :content_rules_created (count succeeded-markers)
             :warnings (mapv :message failed-markers)}))))))

;; FEED ITEMS

(defn tool-list-feed-items [{:keys [ids episode_id feed_id series_id itunes_type published q
                                    released_after released_before sort page page_size]}
                            config]
  (let [params (cond-> {}
                 (seq ids) (assoc "ids[]" ids)
                 episode_id (assoc "episode_id" episode_id)
                 (seq feed_id) (assoc "feed_id[]" feed_id)
                 series_id (assoc "series_id" series_id)
                 itunes_type (assoc "itunes_type" itunes_type)
                 (some? published) (assoc "published" published)
                 q (assoc "q" q)
                 released_after (assoc "released_after" released_after)
                 released_before (assoc "released_before" released_before)
                 sort (assoc "sort" sort)
                 true (assoc "page[number]" (str (or page 1)))
                 true (assoc "page[size]" (str (or page_size 100))))
        resp (api-get "/feed_items" params config)]
    (if (:error resp)
      resp
      {:feed_items (mapv (fn [fi]
                           {:id (:id fi)
                            :title (get-in fi [:attributes :title])
                            :status (get-in fi [:attributes :status])
                            :published (get-in fi [:attributes :published])
                            :itunes_type (get-in fi [:attributes :itunes_type])
                            :released_at (get-in fi [:attributes :released_at])
                            :enclosure_url (get-in fi [:attributes :enclosure_url])})
                         (get-in resp [:data :data]))
       :total (get-in resp [:data :meta :total])
       :page page
       :links (get-in resp [:data :links])})))

(defn tool-get-feed-item [{:keys [feed_item_id]} config]
  (let [resp (api-get (str "/feed_items/" feed_item_id) {} config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-create-feed-item [{:keys [series_id feed_id title description itunes_type
                                     released_at premium_status]}
                             config]
  (when (str/blank? title) (throw (ex-info "title is required" {:type :bad-request})))
  (let [attrs (cond-> {:title title}
                description (assoc :description description)
                itunes_type (assoc :itunes_type itunes_type)
                released_at (assoc :released_at released_at)
                premium_status (assoc :premium_status premium_status))
        rels (cond-> {}
               series_id (assoc :series {:data {:type "series" :id series_id}})
               feed_id (assoc :feed {:data {:type "feeds" :id feed_id}}))
        body {:data {:type "feed_items" :attributes attrs :relationships rels}}
        resp (api-post "/feed_items" body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-update-feed-item [{:keys [feed_item_id title description itunes_type published
                                     released_at premium_status]}
                             config]
  (let [attrs (cond-> {}
                title (assoc :title title)
                description (assoc :description description)
                itunes_type (assoc :itunes_type itunes_type)
                (some? published) (assoc :published published)
                released_at (assoc :released_at released_at)
                premium_status (assoc :premium_status premium_status))
        body {:data {:type "feed_items" :id feed_item_id :attributes attrs}}
        resp (api-patch (str "/feed_items/" feed_item_id) body config)]
    (if (:error resp)
      resp
      (:data resp))))

(defn tool-delete-feed-item [{:keys [feed_item_id]} config]
  (let [resp (api-delete (str "/feed_items/" feed_item_id) config)]
    (if (:error resp)
      resp
      {:deleted feed_item_id})))

;; ─── Tool Registry ──────────────────────────────────────────────────────────

(def tools
  [   {:name "list_episodes"
    :description "List episodes. IMPORTANT: You MUST provide one of: series_id, series_slug, or season_id. Supports filtering by status, date range, and search query."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of episode UUIDs to fetch"}
                               :series_id {:type "string" :description "Series UUID (use series_slug for JB shows)"}
                               :series_slug {:type "string" :description "Series slug or alias: lu, linux-unplugged, twib, tl, cr, sh, self-hosted, coder-radio, the-launch"}
                               :season_id {:type "string" :description "Filter by season UUID"}
                               :published {:type "boolean" :description "Filter to published (true) or unpublished (false) episodes"}
                               :q {:type "string" :description "Search episodes by title"}
                               :sort {:type "string" :description "How to sort results (default: sort_title). Valid values: sort_title, title, released_at, earliest_released_at, released_or_created_at, created_at, updated_at. Can be comma-separated for multiple sorts. Prefix with - for descending, e.g. -released_at."}
                               :year {:type "string" :description "Filter by release year (e.g., '2026')"}
                               :month {:type "string" :description "Filter by release month (e.g., '1' through '12')"}
                               :released_after {:type "string" :description "ISO 8601 timestamp — only episodes released after this"}
                               :released_before {:type "string" :description "ISO 8601 timestamp — only episodes released before this"}
                               :itunes_type {:type "string" :description "Filter by episode type: full, trailer, bonus"}
                               :page {:type "integer" :description "Page number (default: 1)"}
                               :page_size {:type "integer" :description "Results per page (max 100, default 100)"}}
                  :required []}}

   {:name "get_episode"
    :description "Get full details for a single episode by ID. To get episode credits (hosts, guests, producers), use the list_credits tool."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :include {:type "string" :description "Related resources to include. Valid options: active_version, cover_image, season, series, series.cover_image, series.network. Use list_credits tool to get credits."}}
                  :required ["episode_id"]}}

   {:name "create_episode"
    :description "Create a new episode in draft status."
    :inputSchema {:type "object"
                  :properties {:series_slug {:type "string" :description "JB show alias: lu, twib, tl, cr, sh"}
                               :series_id {:type "string" :description "Series UUID (alternative to series_slug)"}
                               :title {:type "string" :description "Episode title"}
                               :description {:type "string" :description "Episode description (HTML supported)"}
                               :description_is_html {:type "boolean" :description "Set to true if the description field contains HTML markup. Defaults to false (plain text)."}
                               :season_id {:type "string" :description "Season UUID to assign episode to"}
                               :itunes_type {:type "string" :description "Episode type: full, trailer, bonus"}
                               :premium_status {:type "string" :description "Premium status: active, inactive, force-active, force-inactive"}
                               :released_at {:type "string" :description "ISO 8601 release datetime"}
                               :release_end_at {:type "string" :description "ISO 8601 datetime when the episode will be removed from the feed. The episode stays published but becomes inaccessible after this time."}
                               :release_immediately {:type "boolean" :description "Release immediately upon publishing"}}
                  :required ["title"]}}

   {:name "update_episode"
    :description "Update metadata on an existing episode."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :title {:type "string" :description "Episode title"}
                               :description {:type "string" :description "Episode description (HTML supported)"}
                               :description_is_html {:type "boolean" :description "Set to true if the description field contains HTML markup. Defaults to false (plain text)."}
                               :published {:type "boolean" :description "Set to true to publish, false to unpublish"}
                               :released_at {:type "string" :description "ISO 8601 release datetime"}
                               :release_end_at {:type "string" :description "ISO 8601 datetime when the episode will be removed from the feed."}
                               :release_immediately {:type "boolean" :description "Release immediately"}
                               :itunes_type {:type "string" :description "Episode type: full, trailer, bonus"}
                               :premium_status {:type "string" :description "Premium status: active, inactive, force-active, force-inactive"}
                               :cover_image_id {:type "string" :description "Cover image UUID"}
                               :allow_user_comments {:type "boolean" :description "Allow user comments"}}
                  :required ["episode_id"]}}

   {:name "delete_episode"
    :description "Permanently delete an episode."
    :inputSchema {:type "object" :properties {:episode_id {:type "string"}} :required ["episode_id"]}}

   {:name "publish_episode"
    :description "Publish an episode. If released_at is provided and is in the future, the episode is marked published but will not appear in the feed until that time. If release_immediately is true or released_at is in the past, it goes live right away."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :released_at {:type "string" :description "ISO 8601 datetime to schedule release"}
                               :release_immediately {:type "boolean" :description "If true, release immediately"}}
                  :required ["episode_id"]}}

   {:name "list_series"
    :description "List all podcast series/shows available in the ART19 account."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of series UUIDs to fetch"}
                               :q {:type "string" :description "Filter series by title or slug (case-insensitive)."}
                               :sort {:type "string" :description "Sort order: created_at, episode_released_at, sort_title, title, updated_at"}}
                  :required []}}

   {:name "get_series"
    :description "Get details for a series. To get seasons, use the list_seasons tool. IMPORTANT: You MUST provide one of: series_slug or series_id."
    :inputSchema {:type "object"
                  :properties {:series_slug {:type "string" :description "JB show alias: lu, twib, tl, cr, sh"}
                               :series_id {:type "string" :description "Series UUID (alternative)"}
                               :include {:type "string" :description "Related resources to include. Valid options: cover_image, network"}}
                  :required []}}

   {:name "list_seasons"
    :description "List seasons for a series. IMPORTANT: You MUST provide one of: series_slug or series_id."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of season UUIDs to fetch"}
                               :series_slug {:type "string" :description "Series slug or JB alias"}
                               :series_id {:type "string" :description "Series UUID"}
                               :q {:type "string" :description "Filter seasons by title (case-insensitive search)."}
                               :sort {:type "string" :description "Sort order: created_at, sort_title, title, updated_at"}}
                  :required []}}

   {:name "get_season"
    :description "Get details for a season."
    :inputSchema {:type "object"
                  :properties {:season_id {:type "string" :description "Season UUID"}
                               :include {:type "string" :description "Related resources to include. Valid options: cover_image, series, series.cover_image, series.network"}}
                  :required ["season_id"]}}

   {:name "list_credits"
    :description "List credits (hosts, guests, producers) for an episode."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of credit UUIDs to fetch"}
                               :episode_id {:type "string" :description "Filter by episode UUID"}
                               :sort {:type "string" :description "Sort order: created_at, position, updated_at"}}
                  :required ["episode_id"]}}

   {:name "add_credit"
    :description "Add a credit to an episode."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string"}
                               :person_id {:type "string" :description "Person UUID (use search_people to find)"}
                               :role {:type "string" :description "Credit role. Valid values: AnchorCredit, AssociateProducerCredit, AuthorCredit, CastCredit, CoHostCredit, ComposerCredit, CreatorCredit, Credit, DirectorCredit, EditorCredit, EngineerCredit, ExecutiveProducerCredit, GuestCoHostCredit, GuestCredit, GuestHostCredit, HeadWriterCredit, HostCredit, ProducerCredit, ReporterCredit, SeniorProducerCredit, SidekickCredit, VideoProducerCredit, WriterCredit"}}
                  :required ["episode_id" "person_id" "role"]}}

   {:name "update_credit"
    :description "Update the role on an existing credit."
    :inputSchema {:type "object"
                  :properties {:credit_id {:type "string"}
                               :role {:type "string"}}
                  :required ["credit_id" "role"]}}

   {:name "remove_credit"
    :description "Remove a credit from an episode."
    :inputSchema {:type "object" :properties {:credit_id {:type "string"}} :required ["credit_id"]}}

   {:name "search_people"
    :description "Search for people (hosts, guests) by name."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of person UUIDs to fetch"}
                               :q {:type "string" :description "Name search query"}
                               :sort {:type "string" :description "Sort order: created_at, first_name, last_name, updated_at"}
                               :page {:type "integer"}
                               :page_size {:type "integer"}}
                  :required ["q"]}}

   {:name "get_person"
    :description "Get details for a person."
    :inputSchema {:type "object" :properties {:person_id {:type "string"}} :required ["person_id"]}}

   {:name "create_person"
    :description "Create a new person record."
    :inputSchema {:type "object"
                  :properties {:first_name {:type "string"}
                               :last_name {:type "string"}
                               :email {:type "string" :description "Public email address"}
                               :bio {:type "string" :description "Biography text"}
                               :born {:type "string" :description "Date of birth (ISO 8601)"}
                               :died {:type "string" :description "Date of death (ISO 8601)"}
                               :from_country {:type "string" :description "Country of origin"}
                               :from_locality {:type "string" :description "City of origin"}
                               :from_region {:type "string" :description "Region/state of origin"}
                               :private_email {:type "string" :description "Private email (not published)"}}
                  :required ["first_name" "last_name"]}}

   {:name "list_episode_versions"
    :description "List audio versions for an episode. Each version corresponds to an audio file."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of version UUIDs to fetch"}
                               :episode_id {:type "string" :description "Filter by episode UUID"}
                               :feed_item_id {:type "string" :description "Filter by feed item UUID"}}
                  :required []}}

   {:name "create_episode_version"
    :description "Create a new audio version for an episode by providing a source URL. The audio will be fetched and processed by ART19."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string"}
                               :source_url {:type "string" :description "Public URL to the audio file (MP3/WAV)"}
                               :copy_active_version {:type "boolean" :description "If true, copies the audio file and source URL from the currently active episode version. Cannot be combined with source_url."}
                               :copy_marker_points {:type "boolean" :description "If true, copies all marker points from the currently active episode version."}}
                  :required ["episode_id" "source_url"]}}

   {:name "get_episode_version"
    :description "Get processing status and details for an episode version."
    :inputSchema {:type "object" :properties {:version_id {:type "string"}} :required ["version_id"]}}

   {:name "delete_episode_version"
    :description "Delete an episode version."
    :inputSchema {:type "object" :properties {:version_id {:type "string"}} :required ["version_id"]}}

   {:name "update_episode_version"
    :description "Update an episode version. Use to submit a version for processing by setting processing_status to 'submitted'. Also used to make active versions inactive, update source_url, or set status_on_completion."
    :inputSchema {:type "object"
                  :properties {:version_id {:type "string" :description "Episode version UUID"}
                               :processing_status {:type "string" :description "Set to 'submitted' to start processing, 'inactive' to deactivate"}
                               :source_url {:type "string" :description "URL to audio file (MP3/WAV)"}
                               :status_on_completion {:type "string" :description "Status after processing: active or inactive"}
                               :copy_active_version {:type "boolean" :description "Copy audio from currently active version"}
                               :copy_marker_points {:type "boolean" :description "Copy marker points from currently active version"}}
                  :required ["version_id"]}}

   {:name "get_episode_next_sibling"
    :description "Get the episode released immediately after this one."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :rss {:type "boolean" :description "If true, only return episodes that are released, published, and have media available"}}
                  :required ["episode_id"]}}

   {:name "get_episode_previous_sibling"
    :description "Get the episode released immediately before this one."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :rss {:type "boolean" :description "If true, only return episodes that are released, published, and have media available"}}
                  :required ["episode_id"]}}

   {:name "upload_image"
    :description "Upload an image for series/season/episode artwork. The image will be processed by ART19. After uploading, monitor the image status (uploaded -> processing -> valid)."
    :inputSchema {:type "object"
                  :properties {:source_url {:type "string" :description "Public URL to the image file (JPG, PNG)"}
                               :series_id {:type "string" :description "Series UUID (required - this is the 'bucket' owner)"}
                               :crop_data {:type "object" :description "Crop area: {left, top, width, height}"
                                           :properties {:left {:type "number"}
                                                        :top {:type "number"}
                                                        :width {:type "number"}
                                                        :height {:type "number"}}}}
                  :required ["source_url" "series_id"]}}

   {:name "list_media_assets"
    :description "List media assets (audio/image files) for an episode version. Returns url, duration_in_ms, file_size, content_type. NOTE: Returns empty array after episode is published - use get_episode to find the enclosure_url from feed_items instead."
    :inputSchema {:type "object"
                  :properties {:attachment_id {:type "string" :description "Episode version UUID to get media assets for"}
                               :attachment_type {:type "string" :description "Type: EpisodeVersion (works but not documented)"}}
                  :required []}}

   {:name "list_marker_points"
    :description "List chapter/ad insertion marker points for an episode version. Returns id, position_type_name (preroll/midroll/postroll), start_position, type."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of marker point UUIDs to fetch"}
                               :episode_version_id {:type "string" :description "Filter by a specific episode version UUID."}
                               :episode_id {:type "string" :description "Filter by episode ID — returns markers for the live active version of that episode. Use this instead of episode_version_id when you only have the episode ID."}
                               :series_id {:type "string" :description "Filter by series ID"}
                               :season_id {:type "string" :description "Filter by season ID"}
                               :type {:type "string" :description "Filter by marker type (e.g., AdInsertionPoint)"}
                               :page {:type "integer" :description "Page number"}
                               :page_size {:type "integer" :description "Results per page (max 100)"}}
                  :required []}}

   {:name "create_marker_point"
    :description "Create a chapter or ad insertion marker point on an episode version. position_type: 0=preroll, 1=midroll, 2=postroll."
    :inputSchema {:type "object"
                  :properties {:episode_version_id {:type "string"}
                               :position_type {:type "integer" :description "0=preroll, 1=midroll, 2=postroll"}
                               :start_position {:type "number" :description "Position in seconds"}
                               :type {:type "string" :description "Marker type. Only valid value: AdInsertionPoint"}
                               :maximum_content_duration {:type "number" :description "Required. Max total ad time in seconds (float)."}
                               :maximum_content_count {:type "integer" :description "Required. Max number of ads at this marker."}}
                  :required ["episode_version_id" "position_type" "type" "maximum_content_count" "maximum_content_duration"]}}

   {:name "delete_marker_point"
    :description "Delete a marker point."
    :inputSchema {:type "object" :properties {:marker_point_id {:type "string"}} :required ["marker_point_id"]}}

   {:name "get_marker_point"
    :description "Get full details for a single marker point by ID."
    :inputSchema {:type "object" :properties {:marker_point_id {:type "string" :description "Marker point UUID"}} :required ["marker_point_id"]}}

   {:name "update_marker_point"
    :description "Update a marker point's timing or ad limits."
    :inputSchema {:type "object"
                  :properties {:marker_point_id {:type "string" :description "Marker point UUID"}
                               :position_type {:type "integer" :description "0=preroll, 1=midroll, 2=postroll"}
                               :start_position {:type "number" :description "Position in seconds"}
                               :maximum_content_duration {:type "number" :description "Max total ad time in seconds"}
                               :maximum_content_count {:type "integer" :description "Max number of ads at this marker"}}
                  :required ["marker_point_id"]}}

   {:name "list_marker_point_content_rules"
    :description "List ad targeting rules for marker points. Without content rules, markers will not serve any ads."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of content rule UUIDs to fetch"}
                               :marker_point_id {:type "string" :description "Filter by marker point UUID"}
                               :sort {:type "string" :description "Sort order: created_at, priority, updated_at"}
                               :page {:type "integer" :description "Page number"}
                               :page_size {:type "integer" :description "Results per page (max 100)"}}
                  :required []}}

   {:name "create_marker_point_content_rule"
    :description "Create an ad targeting rule for a marker point. Without content rules, markers will not serve any ads."
    :inputSchema {:type "object"
                  :properties {:marker_point_id {:type "string" :description "Marker point UUID"}
                               :priority {:type "integer" :description "Required. Higher = checked first."}
                               :content_type {:type "string" :description "Campaign (all ads), LiveReadAd (live reads), TraditionalAd (spots)"}
                               :start_at {:type "string" :description "ISO 8601 datetime. Rule becomes active at this time."}
                               :end_at {:type "string" :description "ISO 8601 datetime. Rule expires at this time."}
                               :content_id {:type "string" :description "Target a specific brand, campaign, or advertisement UUID."}
                               :content_type_target {:type "string" :description "Type of content_id: 'brands', 'campaigns', or 'advertisements'."}}
                  :required ["marker_point_id" "priority"]}}

   {:name "update_marker_point_content_rule"
    :description "Update an ad targeting rule on a marker point."
    :inputSchema {:type "object"
                  :properties {:content_rule_id {:type "string" :description "Content rule UUID"}
                               :priority {:type "integer" :description "Higher = checked first."}
                               :content_type {:type "string" :description "Campaign, LiveReadAd, or TraditionalAd"}
                               :start_at {:type "string" :description "ISO 8601 datetime"}
                               :end_at {:type "string" :description "ISO 8601 datetime"}
                               :content_id {:type "string" :description "Target UUID"}
                               :content_type_target {:type "string" :description "brands, campaigns, or advertisements"}}
                  :required ["content_rule_id"]}}

   {:name "delete_marker_point_content_rule"
    :description "Delete an ad targeting rule from a marker point."
    :inputSchema {:type "object" :properties {:content_rule_id {:type "string"}} :required ["content_rule_id"]}}

   {:name "prepare_episode_version"
    :description "Create a new version of an episode, add ad markers, and submit for processing. Copies audio from the active version. Returns the new version's status — poll get_episode_version until processing completes."
    :inputSchema {:type "object"
                  :properties {:episode_id {:type "string" :description "Episode UUID"}
                               :markers {:type "array"
                                         :description "Ad markers to add. Each marker gets a default content rule (Campaign, priority 1). Override per-marker below. If markers is omitted, copies existing markers from active version."
                                         :items {:type "object"
                                                 :properties
                                                 {:start_position {:type "number" :description "Seconds into audio. Required for midroll/preroll."}
                                                  :position_type {:type "integer" :description "0=preroll, 1=midroll, 2=postroll. Default: 1 (midroll)"}
                                                  :maximum_content_count {:type "integer" :description "Max ads at this marker. Default: 2"}
                                                  :maximum_content_duration {:type "number" :description "Max total ad seconds. Default: 120"}
                                                  :type {:type "string" :description "AdInsertionPoint or EmbeddedAdPoint. Default: AdInsertionPoint"}
                                                  :content_type {:type "string" :description "Campaign=all, LiveReadAd=live reads, TraditionalAd=spots. Default: Campaign"}
                                                  :priority {:type "integer" :description "Content rule priority (higher=checked first). Default: 1"}
                                                  :start_at {:type "string" :description "ISO 8601 datetime. Content rule becomes active at this time."}
                                                  :end_at {:type "string" :description "ISO 8601 datetime. Content rule expires at this time."}
                                                  :content_id {:type "string" :description "Target a specific brand, campaign, or advertisement UUID."}
                                                  :content_type_target {:type "string" :description "Type of content_id: 'brands', 'campaigns', or 'advertisements'."}}}}
                               :status_on_completion {:type "string" :description "active or inactive. Default: active"}
                               :released_at {:type "string" :description "ISO 8601 datetime to schedule release. Omit for immediate."}}
                  :required ["episode_id"]}}

   {:name "list_feed_items"
    :description "List feed items. Returns id, title, status, published, itunes_type, released_at, and enclosure_url (public MP3 URL). IMPORTANT: You MUST provide one of: ids, episode_id, feed_id, or series_id."
    :inputSchema {:type "object"
                  :properties {:ids {:type "array" :items {:type "string"} :description "List of feed item IDs to filter by"}
                               :episode_id {:type "string" :description "Filter by episode ID"}
                               :feed_id {:type "array" :items {:type "string"} :description "Filter by feed UUID(s)"}
                               :series_id {:type "string" :description "Filter by series ID"}
                               :itunes_type {:type "string" :description "Filter by iTunes type: full, bonus, trailer"}
                               :published {:type "boolean" :description "Filter to published (true) or unpublished (false)"}
                               :q {:type "string" :description "Search feed items by title"}
                               :released_after {:type "string" :description "ISO 8601 timestamp — only feed items released after this"}
                               :released_before {:type "string" :description "ISO 8601 timestamp — only feed items released before this"}
                               :sort {:type "string" :description "Sort order. Valid: created_at, latest_released_at, latest_released_at_or_episode_created_at, primary_feed, released_at, released_or_created_at"}
                               :page {:type "integer" :description "Page number"}
                               :page_size {:type "integer" :description "Results per page (max 100)"}}
                  :required []}}

   {:name "get_feed_item"
    :description "Get details for a single feed item by ID."
    :inputSchema {:type "object"
                  :properties {:feed_item_id {:type "string" :description "Feed item UUID"}}
                  :required ["feed_item_id"]}}

   {:name "create_feed_item"
    :description "Create a new feed item. Provide series_id or feed_id to associate with a series or feed."
    :inputSchema {:type "object"
                  :properties {:series_id {:type "string" :description "Series UUID"}
                               :feed_id {:type "string" :description "Feed UUID (alternative to series_id)"}
                               :title {:type "string" :description "Feed item title"}
                               :description {:type "string" :description "Description (HTML supported if description_is_html is true)"}
                               :itunes_type {:type "string" :description "Type: full, bonus, trailer"}
                               :released_at {:type "string" :description "ISO 8601 release datetime"}
                               :premium_status {:type "string" :description "Premium status: active, inactive"}}
                  :required ["title"]}}

   {:name "update_feed_item"
    :description "Update metadata on an existing feed item."
    :inputSchema {:type "object"
                  :properties {:feed_item_id {:type "string" :description "Feed item UUID"}
                               :title {:type "string" :description "Feed item title"}
                               :description {:type "string" :description "Description (HTML supported if description_is_html is true)"}
                               :itunes_type {:type "string" :description "Type: full, bonus, trailer"}
                               :published {:type "boolean" :description "Set to true to publish, false to unpublish"}
                               :released_at {:type "string" :description "ISO 8601 release datetime"}
                               :premium_status {:type "string" :description "Premium status: active, inactive"}}
                  :required ["feed_item_id"]}}

   {:name "delete_feed_item"
    :description "Permanently delete a feed item."
    :inputSchema {:type "object" :properties {:feed_item_id {:type "string"}} :required ["feed_item_id"]}}])

;; ─── Tool Dispatch ──────────────────────────────────────────────────────────

(defn dispatch-tool [name args config]
  (case name
    "list_episodes" (tool-list-episodes args config)
    "get_episode" (tool-get-episode args config)
    "create_episode" (tool-create-episode args config)
    "update_episode" (tool-update-episode args config)
    "delete_episode" (tool-delete-episode args config)
    "publish_episode" (tool-publish-episode args config)
    "list_series" (tool-list-series args config)
    "get_series" (tool-get-series args config)
    "list_seasons" (tool-list-seasons args config)
    "get_season" (tool-get-season args config)
    "list_credits" (tool-list-credits args config)
    "add_credit" (tool-add-credit args config)
    "update_credit" (tool-update-credit args config)
    "remove_credit" (tool-remove-credit args config)
    "search_people" (tool-search-people args config)
    "get_person" (tool-get-person args config)
    "create_person" (tool-create-person args config)
    "list_episode_versions" (tool-list-versions args config)
    "create_episode_version" (tool-create-version args config)
    "get_episode_version" (tool-get-version args config)
    "delete_episode_version" (tool-delete-version args config)
    "update_episode_version" (tool-update-version args config)
    "get_episode_next_sibling" (tool-get-next-sibling args config)
    "get_episode_previous_sibling" (tool-get-previous-sibling args config)
    "upload_image" (tool-upload-image args config)
    "list_media_assets" (tool-list-media-assets args config)
    "list_marker_points" (tool-list-marker-points args config)
    "create_marker_point" (tool-create-marker-point args config)
    "delete_marker_point" (tool-delete-marker-point args config)
    "get_marker_point" (tool-get-marker-point args config)
    "update_marker_point" (tool-update-marker-point args config)
    "list_marker_point_content_rules" (tool-list-marker-point-content-rules args config)
    "create_marker_point_content_rule" (tool-create-marker-point-content-rule args config)
    "update_marker_point_content_rule" (tool-update-marker-point-content-rule args config)
    "delete_marker_point_content_rule" (tool-delete-marker-point-content-rule args config)
    "prepare_episode_version" (tool-prepare-episode-version args config)
    "list_feed_items" (tool-list-feed-items args config)
    "get_feed_item" (tool-get-feed-item args config)
    "create_feed_item" (tool-create-feed-item args config)
    "update_feed_item" (tool-update-feed-item args config)
    "delete_feed_item" (tool-delete-feed-item args config)
    {:error (str "Unknown tool: " name)}))

;; ─── JSON-RPC Handlers ──────────────────────────────────────────────────────

(defn handle-initialize [id _params]
  {:jsonrpc "2.0"
   :id id
   :result {:protocolVersion protocol-version
            :capabilities {:tools {:listChanged false}}
            :serverInfo server-info}})

(defn handle-tools-list [id _params]
  {:jsonrpc "2.0"
   :id id
   :result {:tools tools}})

(defn handle-tools-call [id params config]
  (let [tool-name (get params :name)
        args (get params :arguments)]
    ;; Validate arguments is a map (per MCP/JSON-RPC spec)
    (if (and (some? args) (not (map? args)))
      (do
        (log "error" "Tool Call Invalid" {:tool tool-name :error "arguments must be an object"})
        {:jsonrpc "2.0"
         :id id
         :error {:code -32600
                 :message "Invalid Request"
                 :data "arguments must be an object"}})
      (let [args (or args {})]
        (log "info" "Tool Call" {:tool tool-name :args (vec (keys args))})
        (try
          (let [start (System/currentTimeMillis)
                result (dispatch-tool tool-name args config)
                elapsed (- (System/currentTimeMillis) start)
                content (if (:error result)
                          (let [err-msg (or (:message result) (:error result))
                                raw-err (:errors result)
                                full-msg (if raw-err
                                           (str err-msg "\n\nRaw: " (json/generate-string raw-err))
                                           err-msg)]
                            [{:type "text" :text (str "Error: " full-msg)}])
                          [{:type "text"
                            :text (json/generate-string result {:pretty true})}])]
            (log "info" "Tool Result" {:tool tool-name :elapsed_ms elapsed :error (:error result)})
            {:jsonrpc "2.0"
             :id id
             :result {:content content
                      :isError (boolean (:error result))}})
          (catch clojure.lang.ExceptionInfo e
            (log "error" "Tool Exception" {:tool tool-name :error (ex-message e)})
            {:jsonrpc "2.0"
             :id id
             :result {:content [{:type "text" :text (str "Error: " (ex-message e))}]
                      :isError true}})
          (catch Exception e
            (log "error" "Tool Exception" {:tool tool-name :error (.getMessage e)})
            {:jsonrpc "2.0"
             :id id
             :result {:content [{:type "text" :text (str "Error: " (.getMessage e))}]
                      :isError true}}))))))

(defn dispatch-rpc [body config]
  (let [method (keyword (:method body))
        id (:id body)
        params (:params body)]
    (case method
      :initialize (handle-initialize id params)
      :notifications/initialized nil ;; notification, no response
      :tools/list (handle-tools-list id params)
      :tools/call (handle-tools-call id params config)
      ;; Unknown method
      {:jsonrpc "2.0"
       :id id
       :error {:code -32601 :message (str "Method not found: " (:method body))}})))

;; ─── HTTP Server ────────────────────────────────────────────────────────────

(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn handle-mcp [request config]
  (let [body-stream (:body request)
        body-raw (when body-stream (slurp body-stream))
        body (try (when body-raw (json/parse-string body-raw true))
                  (catch Exception _
                    (json-response 400 {:error "Invalid JSON"})))]
    (cond
      (nil? body-stream)
      (json-response 400 {:error "Missing request body"})

      (contains? body :status) ; it's an HTTP response (error case from parse-string catch)
      body

      :else
      (case (:request-method request)
        :post
        (let [session-id (find-header request "mcp-session-id")
              new-session? (= "initialize" (:method body))
              sid (if new-session?
                    (create-session!)
                    session-id)]
          (log "debug" "MCP Request" {:method (:method body) :session-id sid :new-session? new-session?})
          (if (and (not new-session?) (not (valid-session? sid)))
            (do
              (log "warn" "Session rejected" {:sid sid :active-sessions (keys @sessions)})
              (json-response 400 {:error "Invalid or missing Mcp-Session-Id"}))
            (let [response (dispatch-rpc body config)]
              (if (nil? response)
                {:status 204
                 :headers (cond-> {"Content-Type" "application/json"}
                            new-session? (assoc "Mcp-Session-Id" sid))
                 :body ""}
                {:status 200
                 :headers (cond-> {"Content-Type" "application/json"}
                            new-session? (assoc "Mcp-Session-Id" sid))
                 :body (json/generate-string response)}))))
        {:status 405 :body "Method Not Allowed"}))))

(defn handler [request config]
  (let [uri (:uri request)]
    (cond
      (or (= uri "/mcp") (= uri "/mcp/")) (handle-mcp request config)
      (= uri "/health") {:status 200 :headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:status "ok" :server "art19-mcp"})}
      :else {:status 404 :body "Not Found"})))

;; ─── Entry Point ────────────────────────────────────────────────────────────

(defn -main [& _args]
  (let [config (get-art19-config)
        port (or (some-> (System/getenv "ART19_MCP_PORT") Integer/parseInt) 0)
        srv (http/run-server
             (fn [req] (handler req config))
             {:port port :ip "127.0.0.1"})
        actual-port (:local-port (meta srv))]
    (when (or (str/blank? (:api-token config))
              (str/blank? (:api-credential config)))
      (binding [*out* *err*]
        (println "Warning: ART19_API_TOKEN or ART19_API_CREDENTIAL not set. API calls will fail.")))
    (println (json/generate-string
              {:status "started"
               :server "art19-mcp"
               :port actual-port
               :url (str "http://127.0.0.1:" actual-port "/mcp")
               :tools (count tools)}))
    (deref (promise)))) ;; block forever

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
