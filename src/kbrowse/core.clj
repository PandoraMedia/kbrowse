;;;
;;; Copyright 2017-present Pandora Media, Inc.
;;;
;;; Licensed under the Apache License, Version 2.0 (the "License");
;;; you may not use this file except in compliance with the License.
;;; You may obtain a copy of the License at
;;;
;;;  http://www.apache.org/licenses/LICENSE-2.0
;;;
;;; Unless required by applicable law or agreed to in writing, software
;;; distributed under the License is distributed on an "AS IS" BASIS,
;;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;; See the License for the specific language governing permissions and
;;; limitations under the License.
;;;

(ns kbrowse.core
  "Browse and search a kafka cluser via CLI, and/or a web ui for convenient access."
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [kbrowse.cli :as cli]
            [kbrowse.config :as config]
            [kbrowse.kafka :as kafka]
            [kbrowse.search :as search]
            [kbrowse.topics :as topics]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.resource :as resource]
            [ring.util.response :as response]
            [ring.util.io :as ring-io]
            [ring.util.response :as response])
  (:import [org.apache.avro.generic GenericData$Record]
           [java.io InputStream IOException]
           [java.net URLDecoder]
           [org.apache.avro.Schema])
  (:gen-class))

(def query-cache
  "Cache query results for fast retrieval when users share
   their findings via console URL."
  (-> (com.google.common.cache.CacheBuilder/newBuilder)
      (.expireAfterWrite config/cache-ttl-minutes java.util.concurrent.TimeUnit/MINUTES)
      (.maximumSize config/cache-max-items)
      (.build)))

(def skip-cache-marker
  "If a result is too big, stop buffering it and do not cache.
   We mark the start of the buffer as a side effect that we can check.
   This zero character works because it differs from the search/pioneer,
   so we know we're not ignoring a legitimate result."
  (char 0))

(defn to-request-args
  "Parse request args into a map."
  [request]
  (when-let [query-string (:query-string request)]
    (->> (URLDecoder/decode query-string "UTF-8")
         (#(string/split % #"&"))
         (#(for [s %] (string/split s #"=")))
         (into {}))))

(defn to-cli-args
  "Parse request args map as cli options."
  [request-args]
  (-> request-args
      (#(for [[k v] %] [(str "--" k) v]))
      (into {})
      flatten))

(defn create-output-fn
  "Create a function, which when called, sends the input
   to the user as part of the streaming response."
  [writer cache-buffer]
  (fn [x]
    (try
      (.write writer x)
      (.flush writer)
      (.append cache-buffer x)
      (when (> (.length cache-buffer) config/cache-item-size-limit)
        (when (not= (.charAt cache-buffer 0) skip-cache-marker)
          (.setCharAt cache-buffer 0 skip-cache-marker)))
      (catch IOException e
        ; Happens when client disconnects.
        (log/debug "IOException in search-handler" e)))))

(defn search-handler
  "Search a kafka cluster with the given query parameters,
   returning any matching results."
  [request]
  (try
    (let [cli-args (-> request to-request-args to-cli-args)
          options (-> cli-args cli/parse :options)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (ring-io/piped-input-stream
              (fn [stream]
                (let [writer (io/make-writer stream {})
                      cache-buffer (java.lang.StringBuffer.)]
                  (search/search options (create-output-fn writer cache-buffer))
                  (when (not= (.charAt cache-buffer 0) skip-cache-marker)
                    (.put query-cache cli-args (.toString cache-buffer)))
                  (.close writer))))})
    (catch IllegalArgumentException e
      (log/error e)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (cheshire/generate-string {:error (.getMessage e)})})))

(defn cached-handler
  "Check if a query result is already cached."
  [request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (.getIfPresent query-cache (-> request to-request-args to-cli-args))})

(defn default-partition-handler
  "Return the default partition for the given key."
  [request]
  (let [args (to-request-args request)
        bootstrap-servers (get args "bootstrap-servers")
        topic (get args "topic")
        key-regex (get args "key")
        deser "org.apache.kafka.common.serialization.StringDeserializer"
        config (kafka/config bootstrap-servers deser deser true)
        part (kafka/default-partition config topic key-regex)]
    (str part)))

(defn server-configs-handler
  "Helper for the web console, to give an easy way of populating dropdowns."
  [request]
  (let [deserializers (if config/kafka-schema-registry-urls
                        kafka/deserializers
                        (dissoc kafka/deserializers :avro))]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (cheshire/generate-string {:bootstrap-servers config/kafka-bootstrap-servers
                                      :bootstrap-topics @topics/bootstrap-topics
                                      :value-deserializers deserializers
                                      :schema-registry-urls config/kafka-schema-registry-urls})}))

(defroutes app
  (route/resources "/")
  (GET "/" []
    (-> (response/resource-response "index.html" {:root "public"})
        (response/content-type "text/html")))
  (GET "/favicon.ico" []
    ; Respond to these to avoid log noise
    (response/response "OK"))
  (GET "/health" []
    (response/response "OK"))
  (GET "/search" request
    (search-handler request))
  (GET "/cached" request
    (cached-handler request))
  (GET "/default-partition" request
    (default-partition-handler request))
  (GET "/server-configs" request
    (server-configs-handler request)))

(defn -main
  "Entrypoint for either cli or starting a server"
  [main & args]
  (topics/init)
  (case main
    "cli" (do
            (let [parsed (cli/parse args)
                  options (:options parsed)]
              (if (:help options)
                (println (:summary parsed))
                (search/search options println))
              (System/exit 0)))
    "server" (do (jetty/run-jetty app {:port config/port
                                       :join? false
                                          ; Prevent buffering response, which sets
                                          ; "Content-Length" and removes
                                          ; "Transfer-Encoding": "chunked"
                                       :output-buffer-size 100})
                 (println (format "\nKBrowse Ready...\nhttp://localhost:%s" config/port)))))
