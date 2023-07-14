(ns org.pilosus.github.api.async
  (:require
   [cheshire.core :as json]
   [clojure.string :as s]
   [clojure.core.async :as async]
   [org.httpkit.client :as http]))

;; Helper functions & const

(def api-handler-base "https://api.github.com/repos")

(def delay-offset 10000)

(def request-counter (atom {:limit 0 :used 0 :reset 0}))

(defn counter-inc!
  []
  (swap! request-counter (fn [s] (update s :used inc))))

(defn counter-set!
  [limit used reset]
  (swap! request-counter (fn [s] (assoc s :limit limit :used used :reset reset))))

(def token-atom (atom nil))

(defn github-token
  "Get GitHub API Token"
  []
  (or (System/getenv "GITHUB_TOKEN") @token-atom))

(defn headers
  "Get header for a HTTP request"
  []
  (let [token (github-token)
        header-token (when token
                       {"Authorization" (format "Bearer %s" token)})]
    (merge
     {"Content-Type" "application/json"
      "X-GitHub-API-Version" "2022-11-28"}
     header-token)))

(defn http-status-ok?
  "Return true if a HTTP status code successful"
  [status-code]
  (and
   (>= status-code 200)
   (< status-code 300)))

(let [log-c (async/chan 1024)]
  (async/go
    (loop []
      (when-some [v (async/<! log-c)]
        (println v)
        (recur))))

  (defn log
    "Simple logging for async debugging"
    [& items]
    (async/>!! log-c (apply str (interpose " " items)))))

(defn counter-init!
  "Init counter with the current GitHub rate limits"
  []
  (let [callback
        (fn [response]
          (try (-> response
                   :body
                   (json/parse-string true)
                   :resources
                   :core)
               (catch Exception _ 60)))
        {:keys [limit used reset]}
        @(http/request
          {:url "https://api.github.com/rate_limit"
           :method :get
           :headers (headers)}
          callback)]
    (counter-set! limit used (* 1000 reset))))

;; Async pipelines

(defn repo-url->api-url
  "Transform a GitHub repository URL to a respective API handler"
  [repo-url]
  (let [url (if repo-url (s/replace repo-url #"/$" "") nil)
        [_ user repo :as parts] (try
                                  (re-find #"^(?:https://github.com)/(.*)/(.*)" url)
                                  (catch Exception _ nil))

        api-url (when (= 3 (count parts))
                  (format "%s/%s/%s" api-handler-base user repo))]
    api-url))

(defn pipe-urls
  "Process URLs to get a GitHub repo's API handler URL"
  [from to threads]
  (let [f (fn [{:keys [url] :as data}]
            (-> data
                (assoc :api-url (repo-url->api-url url))))]
    ;; CPU-bound
    (async/pipeline threads to (map f) from)))

(defn count-stargazers
  "http-kit callback to get stats about GitHub repo stargazers"
  [from-chan-item to-channel response]
  (let [error? (or (not (http-status-ok? (:status response)))
                   (some? (:error response)))
        body (try (-> (:body response)
                      (json/parse-string true))
                  (catch Exception e {:message (str e)}))
        message (when error? (or (:message body) (:error response)))
        stats (if error?
                {:error? error? :message message}
                {:stars (:stargazers_count body)})]
    (counter-inc!)
    (async/put! to-channel (-> from-chan-item
                               (assoc :stats stats)
                               (dissoc :api-url)))
    (async/close! to-channel)))

(defn request-or-wait?
  "Either an HTTP request or waiting for rate limits to reset"
  [{:keys [api-url] :as from-chan-item} to-chan]
  (let [wait? (>= (:used @request-counter)(:limit @request-counter))
        delay (- (:reset @request-counter) (System/currentTimeMillis))
        request {:url api-url :method :get :headers (headers)}
        callback (partial count-stargazers from-chan-item to-chan)]
    (if (and wait? (pos? delay))
      (do
        ;; It's ok to block the whole thread here as rate limits are per user
        (log "Sleep for" delay "milliseconds" "Counter" @request-counter)
        (Thread/sleep (+ delay delay-offset))
        (counter-init!)
        (http/request request callback))
      (http/request request callback))))

(defn pipe-stats
  "Process channel with GitHub repo URLs to get repo stats"
  [from to threads]
  ;; IO-bound async
  (async/pipeline-async threads to request-or-wait? from))

;; Entrypoint

(defn repo-stats
  "Enrich GitHub projects sequence with extra repo stats"
  ([projects] (repo-stats projects {}))
  ([projects opts]
   (let [{:keys [buffer-size threads-urls threads-stats token]
          :or {buffer-size 20 threads-urls 4 threads-stats 4}} opts
         project-urls (async/chan buffer-size)
         api-urls (async/chan buffer-size (remove #(nil? (:api-url %))))
         stars (async/chan buffer-size)]
     (when token (swap! token-atom (constantly token)))
     (counter-init!)
     (async/onto-chan project-urls projects)
     (pipe-urls project-urls api-urls threads-urls)
     (pipe-stats api-urls stars threads-stats)
     (async/<!! (async/into [] stars)))))

(comment
  (def example-projects
    [{:url "https://clojure.org/"}
     {:url "https://example.com/"}
     {:url "https://github.com/clojure/clojure"}
     {:url "https://github.com/clojure/clojurescript"}
     {:url "https://github.com/clojure/core.async"}
     {:url "https://github.com/clojure/core.logic"}
     {:url "https://github.com/clojure/spec.alpha"}
     {:url "https://github.com/clojure/tools.cli"}
     {:url "https://github.com/clojure/tools.deps.cli"}
     {:url "https://github.com/clojure/tools.logging"}]))
