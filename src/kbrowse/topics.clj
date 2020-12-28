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

(ns kbrowse.topics
  "Run a background thread to keep a cache of topics for all clusters.
   This lets us load the web console without having to make a call
   to the kafka clusters, so we can populate the topics dropdown ui."
  (:require [kbrowse.config :as config]
            [kbrowse.kafka :as kafka]))

(def bootstrap-topics
  (atom nil))

(defn refresh
  []
  (->> (doall
        (for [[label bootstrap-servers] config/kafka-bootstrap-servers]
          (future
            (let [deser "org.apache.kafka.common.serialization.StringDeserializer"
                  config (kafka/config bootstrap-servers deser deser true)
                  consumer (kafka/consumer config)
                  topics (kafka/topics consumer)]
              [bootstrap-servers topics]))))
       (map deref)
       (into {})
       (reset! bootstrap-topics)))

(defn init
  []
  ; Update once during initialization
  (refresh)
  ; Keep updating in background
  (future (while true
            (Thread/sleep (* config/kafka-topics-cache-sleep-seconds 1000))
            (try
              (refresh)
              (catch Exception e
                (println "topics/refresh exception - topics may become stale" e))))))
