#!/usr/bin/env bb
;; Pre-flight check for a live-read (EmbeddedAdPoint) publish.
;;
;; Validates the things that fail silently. Run BEFORE publishing any episode
;; with a baked sponsor read.
;;
;;   bb scripts/preflight-live-read.bb <sponsor-name> [expected-duration-seconds]
;;
;; e.g. bb scripts/preflight-live-read.bb Hostinger 72.48
;;
;; Exit 0 = safe to publish. Exit 1 = stop and fix.
;;
;; Reads credentials from ART19_CRED_FILE, defaulting to the systemd-brokered
;; file on the publish host. Values are never printed.

(ns preflight-live-read
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def args *command-line-args*)
(when (empty? args)
  (println "usage: bb scripts/preflight-live-read.bb <sponsor-name> [expected-duration-seconds]")
  (System/exit 2))

(def sponsor (first args))
(def expected-dur (when (second args) (parse-double (second args))))

(def cred-file (or (System/getenv "ART19_CRED_FILE")
                   "/var/lib/hermes/credentials/art19.env"))

(def creds
  (->> (str/split-lines (slurp cred-file))
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (keep (fn [line]
               (let [p (str/split line #"=" 2)]
                 (when (= 2 (count p))
                   [(str/trim (first p)) (str/trim (second p))]))))
       (into {})))

(def auth (str "Token token=\"" (get creds "ART19_API_TOKEN")
               "\", credential=\"" (get creds "ART19_API_CREDENTIAL") "\""))

(defn api [path & [params]]
  (let [u (str "https://art19.com" path
               (when (seq params)
                 (str "?" (str/join "&" (map (fn [[k v]] (str k "=" v)) params)))))]
    (try
      (let [r (http/get u {:headers {"Accept" "application/vnd.api+json"
                                     "Authorization" auth}
                           :throw false :timeout 60000})]
        {:status (:status r)
         :body (try (json/parse-string (:body r) true) (catch Exception _ nil))})
      (catch Exception e {:status :err :body {:msg (.getMessage e)}}))))

(def fails (atom []))
(def warns (atom []))

(defn check [label ok? detail]
  (println (format "  [%s] %-44s %s" (if ok? "PASS" "FAIL") label (or detail "")))
  (when-not ok? (swap! fails conj (str label (when detail (str " — " detail))))))

(defn warn [label detail]
  (println (format "  [warn] %-44s %s" label (or detail "")))
  (swap! warns conj label))

(println (str "\n=== live-read preflight: " sponsor " ===\n"))

;; ── 1. find candidate ads for this sponsor ────────────────────────────────
(def all-ads (get-in (api "/advertisements" {"type" "LiveReadAd"}) [:body :data]))
(def key (str/lower-case sponsor))
(def cands (filter #(str/includes? (str/lower-case (str (get-in % [:attributes :title])))
                                   key)
                   all-ads))

(println "LiveReadAds matching the sponsor name:" (count cands))
(doseq [c cands]
  (let [a (:attributes c)]
    (println (format "   %s  %-44s media=%-5s created=%s"
                     (subs (:id c) 0 8) (:title a) (:media_present a) (:created_at a)))))

(check "at least one candidate ad found" (seq cands)
       (when-not (seq cands)
         (str "no LiveReadAd title contains '" sponsor "' — upload it under "
              "series -> Content -> Advertisements (type Live Read)")))

(when (seq cands)
  ;; prefer newest with audio — mirrors the publisher's ranking
  (def ranked (sort-by (juxt #(if (get-in % [:attributes :media_present]) 1 0)
                             #(get-in % [:attributes :created_at]))
                       #(compare %2 %1)
                       cands))
  (def chosen (first ranked))
  (def ad-id (:id chosen))
  (def ad-title (get-in chosen [:attributes :title]))

  (println (str "\nchosen: " ad-title " (" (subs ad-id 0 8) ")\n"))

  (check "chosen ad has audio (media_present)" (get-in chosen [:attributes :media_present])
         (when-not (get-in chosen [:attributes :media_present])
           "media_present false = unbindable; upload the audio"))

  ;; ── 2. the trafficking chain ───────────────────────────────────────────
  (let [b (:body (api (str "/advertisements/" ad-id)))
        camp (get-in b [:data :relationships :campaign :data :id])]
    (check "ad has a campaign (not nil)" (some? camp)
           (if camp (subs camp 0 8)
               "campaign nil => ART19-only playback, will NOT reach Spotify/Apple"))

    (when camp
      (let [cb (:body (api (str "/campaigns/" camp)))
            ca (get-in cb [:data :attributes])
            rels (get-in cb [:data :relationships])
            brand (get-in rels [:brand :data :id])
            io (get-in rels [:insertion_order :data :id])
            rep (get-in rels [:ad_rep_account :data :id])]
        (check "campaign status is active" (= "active" (:status ca)) (:status ca))
        (check "campaign type is paid" (str/includes? (str (:type ca)) "Paid") (:type ca))
        (check "campaign has a brand" (some? brand) (when brand (subs brand 0 8)))
        (check "campaign has an insertion order" (some? io) (when io (subs io 0 8)))
        (check "campaign has an ad_rep_account" (some? rep) (when rep (subs rep 0 8)))
        (when io
          (warn "line item NOT API-verifiable" (str "IO " (subs io 0 8)
                                                    " — 403 for Series token; glance in Ad Ops UI"))))))

  ;; ── 3. duration cross-check ───────────────────────────────────────────
  (if expected-dur
    (warn "duration needs a manual compare"
          (str "API exposes no ad duration — confirm the ad page shows "
               (format "%.2fs" expected-dur) " (= CSV region width)"))
    (warn "no expected duration supplied"
          "pass the CSV region width as the 2nd arg for a recorded reminder")))

;; ── verdict ──────────────────────────────────────────────────────────────
(println)
(if (seq @fails)
  (do (println "RESULT: NOT SAFE TO PUBLISH")
      (doseq [f @fails] (println "  -" f))
      (System/exit 1))
  (do (println "RESULT: API CHECKS PASS")
      (when (seq @warns)
        (println "Manual checks still required:")
        (doseq [w @warns] (println "  -" w)))
      (System/exit 0)))