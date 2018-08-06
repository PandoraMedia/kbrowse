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

(ns kbrowse.search
  "Filter kafka records to matching results."
  (:require [cheshire.core :as cheshire]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [kbrowse.config :as config]
            [kbrowse.kafka :as kafka]))

(def pioneer
  "JSON maps don't allow trailing commas.
   This is an issue with streaming results, because
   we don't know whether to add a trailing comma or not.

   The workaround is to always send an initial pioneer,
   so that every real result can safely prepend a comma."
  (cheshire/generate-string {:type :pioneer}))

(defn to-map
  "Convert a kafka record object."
  [record]
  {:timestamp (.timestamp record)
   :partition (.partition record)
   :offset (.offset record)
   :topic (.topic record)
   :key (.key record)
   :value (.value record)})

(defn value-to-result-string
  "Attempt to convert result map value to a JSON string.
   If parsing fails, return as is."
  [map*]
  (try
    (-> (:value map*)
        str
        cheshire/parse-string
        (#(assoc map* :value %)))
    (catch Exception e
      map*)))

(defn key-to-result-string
  "Attempt to convert result map key to a JSON string.
   If parsing fails, return as is."
  [map*]
  (try
    (-> (:key map*)
        str
        cheshire/parse-string
        (#(assoc map* :key %)))
    (catch Exception e
      map*)))

(defn to-pretty-print
  "Attempt to convert the restult map into a pretty JSON String.
   If it fails to parse, return as is."
  [map*]
  (cheshire/generate-string map* {:pretty true}))

(defn result?
  "Does this record match the given query params?"
  [key-regex val-regex record]
  (let [record-map (to-map record)
        key-str (str (:key record-map))
        value-str (str (:value record-map))]
    (and (or (nil? key-regex) (re-matches key-regex key-str))
         (or (nil? val-regex) (re-matches val-regex value-str)))))

(defn progress-message
  "Format the progress message for client consumption."
  [record]
  (let [timestamp-date (java.util.Date. (.timestamp record))]
    (-> record
        to-map
        (assoc :type :offset :timestamp timestamp-date)
        (key-to-result-string)
        (value-to-result-string)
        (to-pretty-print)
        (#(str ", " %)))))

(defn maybe-send-progress
  "Intermittently update client."
  [print-offset output-fn record]
  (when (and print-offset
             (= (mod (.offset record) print-offset) 0))
    (-> record progress-message output-fn))
  record)

(defn continue?
  "Continue fetching another batch?"
  [records stop-offsets offsets stop-timestamp stop-running-date follow?]
  (let [record (last records)]
    (and (or follow?
             (and
              ; Are there any topic partitions remaining to be searched,
              ; for the given offset window?
              (some (fn [[k v]]
                      (let [stop-offset (get stop-offsets k)]
                        (or (nil? stop-offset) ; empty topic/partition
                            (< v stop-offset)))) offsets)
              ; Is the record timestamp still within the given time window?
              record
              (not (and stop-timestamp (<= (.timestamp record) stop-timestamp)))))
         ; Cut off any consumers that run for too long.
         ; This could accidentally kill some legitimate queries,
         ; but is a safety trade-off to prevent any forgotten processes
         ; from causing unnecessary cluster load.
         (.before (java.util.Date.) stop-running-date))))

(defn search
  "Iterate a collection of kafka records, specified by the cli options.
   Stream any matching results back via the output function."
  [options output-fn]
  (let [bootstrap-servers (string/split (:bootstrap-servers options) #",")
        key-deser (:key-deserializer options)
        value-deser (:value-deserializer options)
        base-config (kafka/config bootstrap-servers key-deser value-deser true)
        config (cond (or (= value-deser (:avro kafka/deserializers)) (= key-deser (:avro kafka/deserializers)))
                     (assoc base-config "schema.registry.url" (:schema-registry-url options))
                     :else
                     base-config)
        topics (string/split (:topics options) #",")
        string-deser "org.apache.kafka.common.serialization.StringDeserializer"
        partitions-config (kafka/config bootstrap-servers string-deser string-deser true)
        partitions (if (:partitions options)
                     ; User-specified
                     (for [p (string/split (:partitions options) #",")]
                       (int (Integer/parseInt p)))
                     (if (:default-partition options)
                       ; DefaultPartition for the given key (efficient)
                       [(kafka/default-partition partitions-config (first topics) (:key-regex options))]
                       ; Otherwise, default to all partitions (not efficient)
                       (for [p (kafka/partitions-for partitions-config (first topics))]
                         (.partition p))))
        key-regex (if (:key-regex options) (re-pattern (:key-regex options)) nil)
        val-regex (if (:val-regex options) (re-pattern (:val-regex options)) nil)
        print-offset (:print-offset options)
        start-timestamp (:start-timestamp options)
        stop-timestamp (:stop-timestamp options)
        relative-offset (:relative-offset options)
        follow? (:follow options)
        calendar (java.util.Calendar/getInstance)
        _ (.add calendar java.util.Calendar/SECOND config/stop-consumers-after-n-seconds)
        stop-running-date (.getTime calendar)]
    (output-fn "[")
    (output-fn pioneer)
    (let [consumer (kafka/consumer config topics partitions)
          stop-offsets (into {}
                             (for [[k v] (kafka/fetch-offsets consumer)]
                               [k (:latest v)]))]
      (cond
        relative-offset (kafka/seek-to-relative-offset consumer relative-offset)
        :default (kafka/seek-to-beginning consumer))
      (try
        ; Track the topic partition offsets as we consume them,
        ; so we know when to stop fetching new batches.
        (loop [records (kafka/consume consumer)
               offsets {}]
          (let [offsets (into {}
                              (for [record records]
                                (do
                                  (maybe-send-progress print-offset output-fn record)
                                  (let [offsets-key [(.topic record) (.partition record)]
                                        stop-offset (get stop-offsets offsets-key)]
                                    (when (and (or follow? (< (.offset record) stop-offset))
                                               (result? key-regex val-regex record))
                                      (-> record
                                          to-map
                                          (assoc :type :result)
                                          (key-to-result-string)
                                          (value-to-result-string)
                                          (to-pretty-print)
                                          (#(str ", " %))
                                          output-fn))
                                    (assoc offsets offsets-key (.offset record))))))]
            (when (continue? records
                             stop-offsets
                             offsets
                             stop-timestamp
                             stop-running-date
                             follow?)
              (recur (kafka/consume consumer) offsets))))
        (catch Exception e
          (log/error e))
        (finally
          (.close consumer)))
      (output-fn "]"))))
