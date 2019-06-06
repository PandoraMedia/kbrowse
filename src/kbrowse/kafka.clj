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

(ns kbrowse.kafka
  "Wrap the Java client."
  (:require [clojure.string :as string]
            [kbrowse.config :as config]
            ; Ensure msgpack is loaded.
            ; It's not required initially, but may be passed in as a
            ; deserializer to be used when instantiating a consumer.
            [kbrowse.msgpack])
  (:import java.util.ArrayList
           org.apache.kafka.clients.consumer.ConsumerConfig
           org.apache.kafka.clients.consumer.KafkaConsumer
           org.apache.kafka.clients.producer.internals.DefaultPartitioner
           org.apache.kafka.common.Cluster
           org.apache.kafka.common.Node
           org.apache.kafka.common.PartitionInfo
           org.apache.kafka.common.TopicPartition))

(def deserializers
  "Map of labels to deserializers."
  {:string "org.apache.kafka.common.serialization.StringDeserializer"
   :msgpack "kbrowse.msgpack.MsgpackDeserializer"
   :avro "io.confluent.kafka.serializers.KafkaAvroDeserializer"})

(defn config
  "Create a kafka config for instantiating a consumer."
  [bootstrap-servers key-deserializer value-deserializer check-crcs]
  {"bootstrap.servers" bootstrap-servers
   "group.id" "kbrowse"
   "auto.offset.reset" "earliest"
   "enable.auto.commit" "false"
   "key.deserializer" key-deserializer
   "value.deserializer" value-deserializer
   "check.crcs" check-crcs})

(defn partitions-for
  "Get the partitions for the given topics."
  [config topic]
  (let [consumer (KafkaConsumer. config)
        partitions (.partitionsFor consumer topic)]
    (.close consumer)
    partitions))

(defn default-partition
  "Get the default partition for a message key."
  [config topic *key*]
  (let [node (Node. 0 "" 0)
        nodes (into-array Node [node])
        node (Node. 0 "" 0)
        num-partitions (count (partitions-for config topic))
        p-fn #(PartitionInfo. topic % node nodes nodes)
        partitions (ArrayList. (for [p (range num-partitions)] (p-fn p)))
        cluster-nodes (ArrayList. [node])
        cluster (Cluster. nil cluster-nodes partitions #{} #{})]
    (.partition (DefaultPartitioner.) topic nil (.getBytes *key*) nil nil cluster)))

(defn consumer
  "Instantiate a new KafkaConsumer."
  ([config]
   (KafkaConsumer. config))
  ([config topics partitions]
   (let [c (KafkaConsumer. config)
         tps (flatten (for [t topics]
                        (for [p partitions]
                          (TopicPartition. t p))))]
     (.assign c tps)
     c)))

(defn topics
  "Get the topics for the given consumer."
  [consumer]
  (.keySet (.listTopics consumer)))

(defn consume
  "Fetch a batch of messages."
  [consumer]
  (.poll consumer (java.time.Duration/ofMillis config/kafka-timeout)))

(defn seek-to-beginning
  "Set the consumer to the beginning of its assigned topic partitions."
  [consumer]
  (.seekToBeginning consumer (.assignment consumer)))

(defn fetch-offsets
  "Return a map of topic partitions to {:start offset :end offset} maps."
  [consumer]
  (let [tps (.assignment consumer)
        offsets-fn (fn [label]
                     (apply merge
                            (for [tp tps]
                              {[(.topic tp) (.partition tp)] {label (.position consumer tp)}})))
        earliest-offsets (do (.seekToBeginning consumer tps) (offsets-fn :earliest))
        latest-offsets (do (.seekToEnd consumer tps) (offsets-fn :latest))]
    (merge-with merge earliest-offsets latest-offsets)))

(defn seek-to-relative-offset
  "Seek to an offset relative to earliest/latest offsets.
   If value >= 0, seek to earliest + value.
   If value < 0, seek to latest - value."
  [consumer relative-offset]
  (let [offsets (fetch-offsets consumer)]
    (doseq [[[topic part] tp-offsets] offsets]
      (let [tp (TopicPartition. topic part)
            earliest (:earliest tp-offsets)
            latest (:latest tp-offsets)]
        (if (>= relative-offset 0)
          (.seek consumer tp (+ earliest relative-offset))
          (.seek consumer tp (+ latest relative-offset)))))))
