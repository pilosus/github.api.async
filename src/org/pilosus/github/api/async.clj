(ns org.pilosus.github.api.async
  (:require
   [cheshire.core :as json]
   [clojure.string :as s]
   [clojure.core.async :as async]
   [org.httpkit.client :as http]))

;; Const & helper functions

(def options (atom {:token nil :rate-limit false :verbose false}))

(def rate-limiter (atom {:limit 0 :used 0 :reset 0}))

(defn request-headers
  "Get header for a HTTP request"
  []
  (let [token (or (:token @options) (System/getenv "GITHUB_TOKEN"))
        header-token (when token {"Authorization" (format "Bearer %s" token)})]
    (merge
     {"Content-Type" "application/json"
      "X-GitHub-API-Version" "2022-11-28"}
     header-token)))

(defn rate-limiter-init!
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
           :headers (request-headers)}
          callback)]
    (swap!
     rate-limiter
     (fn [s] (assoc s :limit limit :used used :reset (* 1000 reset))))))

(defn opts-init!
  "Init options atom"
  [opts]
  (let [{:keys [token rate-limit verbose]
         :or {rate-limit false verbose false}} opts]
    (swap!
     options
     (fn [s]
       (assoc
        s
        :token token
        :rate-limit rate-limit
        :verbose verbose)))))

(let [log-c (async/chan 1024)]
  (async/go
    (loop []
      (when-some [v (async/<! log-c)]
        (println v)
        (recur))))

  (defn log
    "Simple logging for async code"
    [& items]
    (async/>!! log-c (apply str (interpose " " items)))))

;; Async pipelines

(defn repo-url->api-url
  "Transform a GitHub repository URL to a respective API handler"
  [repo-url]
  (let [url (if repo-url (s/replace repo-url #"/$" "") nil)
        [_ user repo :as parts] (try
                                  (re-find #"^(?:https://github.com)/(.*)/(.*)" url)
                                  (catch Exception _ nil))

        api-url (when (= 3 (count parts))
                  (format "https://api.github.com/repos/%s/%s" user repo))]
    api-url))

(defn process-urls
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
  (let [status (:status response)
        status-ok? (and (>= status 200) (< status 300))
        error? (or (not status-ok?) (some? (:error response)))
        body (try (-> (:body response)
                      (json/parse-string true))
                  (catch Exception e {:message (str e)}))
        message (when error? (or (:message body) (:error response)))
        stats (if error?
                {:error? error? :message message}
                {:stars (:stargazers_count body)})]
    (swap! rate-limiter (fn [s] (update s :used inc)))
    (async/put! to-channel (-> from-chan-item
                               (assoc :stats stats)
                               (dissoc :api-url)))
    (async/close! to-channel)))

(defn request-stats
  "Request repository stats"
  [{:keys [api-url] :as from-chan-item} to-chan]
  (let [use-rate-limit? (:rate-limit @options)
        {:keys [used limit]} @rate-limiter
        rate-limit-exceeded (>= used limit)
        ;; 3s is to compansate possible jitter
        time-to-reset (- (+ (:reset @rate-limiter) 3000)
                         (System/currentTimeMillis))
        block? (and use-rate-limit? rate-limit-exceeded (pos? time-to-reset))
        request {:url api-url :method :get :headers (request-headers)}
        callback (partial count-stargazers from-chan-item to-chan)]
    (if block?
      (do
        ;; The whole thread (or more depending on parallelism) will be blocked
        ;; It's ok, because rate-limits are per user
        (when (:verbose @options)
          (log
           "Rate limit exceeded:"
           "limit:" limit
           "used:" used
           "time to reset, min:" (-> time-to-reset
                                     (/ 1000 60)
                                     float
                                     Math/round)))
        (Thread/sleep time-to-reset)
        (rate-limiter-init!)
        (http/request request callback))
      (http/request request callback))))

(defn process-stats
  "Process repositories statistics"
  [from to threads]
  ;; IO-bound async
  (async/pipeline-async threads to request-stats from))

;; Entrypoint

(defn repo-stats
  "Enrich GitHub projects sequence with extra repo stats"
  ([projects] (repo-stats projects {}))
  ([projects opts]
   (let [{:keys
          [buffer-size
           threads-urls
           threads-stats]
          :or
          {buffer-size 20
           threads-urls 4
           threads-stats 4}} opts
         chan-project-urls (async/chan buffer-size)
         chan-api-urls (async/chan buffer-size (remove #(nil? (:api-url %))))
         chan-stats (async/chan buffer-size)]
     (opts-init! opts)
     (rate-limiter-init!)

     (async/onto-chan chan-project-urls projects)
     (process-urls chan-project-urls chan-api-urls threads-urls)
     (process-stats chan-api-urls chan-stats threads-stats)

     (async/<!! (async/into [] chan-stats)))))

;; REPL payground

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
