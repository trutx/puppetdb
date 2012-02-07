;; ## "The Kitchen Sink"
;;
;; Pretty much everything in here should _probably_ be organized into
;; proper namespaces, or perhaps even separate libraries
;; altogether. But who has time for that?

(ns com.puppetlabs.utils
  (:import (org.ini4j Ini))
  (:require [clojure.test]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [cheshire.core :as json]
            [ring.util.response :as rr])
  (:use [clojure.core.incubator :only (-?>)]
        [clojure.java.io :only (reader)]
        [clojure.set :only (difference union)]
        [slingshot.core :only (try+ throw+)]))

;; ## Math

(defn quotient
  "Performs division on the supplied arguments, substituting `default`
  when the divisor is 0"
  ([dividend divisor]
     (quotient dividend divisor 0))
  ([dividend divisor default]
     (if (zero? divisor)
       default
       (/ dividend divisor))))

;; ## Collection operations

(defn symmetric-difference
  "Computes the symmetric difference between 2 sets"
  [s1 s2]
  (union (difference s1 s2) (difference s2 s1)))

(defn as-collection
  "Returns the item wrapped in a collection, if it's not one
  already. Returns a list by default, or you can use a constructor func
  as the second arg."
  ([item]
     (as-collection item list))
  ([item constructor]
     {:post [(coll? %)]}
     (if (coll? item)
       item
       (constructor item))))

(defn mapvals
  "Return map `m`, with each value transformed by function `f`"
  [f m]
  (into {} (concat (for [[k v] m]
                     [k (f v)]))))

(defn mapkeys
  "Return map `m`, with each key transformed by function `f`"
  [f m]
  (into {} (concat (for [[k v] m]
                     [(f k) v]))))

(defn array?
  "Returns true if `x` is an array"
  [x]
  (-?> x
       (class)
       (.isArray)))

(defn keyset
  "Retuns a set of keys from the supplied map"
  [m]
  {:pre  [(map? m)]
   :post [(set? %)]}
  (set (keys m)))

;; ## Stubbing
;;
;; These redef functions are backported from Clojure 1.3 core

(defn with-redefs-fn
  "Temporarily redefines Vars during a call to func.  Each val of
  binding-map will replace the root value of its key which must be
  a Var.  After func is called with no args, the root values of all
  the Vars will be set back to their old values.  These temporary
  changes will be visible in all threads.  Useful for mocking out
  functions during testing."
  {:added "1.3"}
  [binding-map func]
  (let [root-bind (fn [m]
                    (doseq [[a-var a-val] m]
                      (.bindRoot ^clojure.lang.Var a-var a-val)))
        old-vals (zipmap (keys binding-map)
                         (map deref (keys binding-map)))]
    (try
      (root-bind binding-map)
      (func)
      (finally
        (root-bind old-vals)))))

(defmacro with-redefs
  "binding => var-symbol temp-value-expr

  Temporarily redefines Vars while executing the body.  The
  temp-value-exprs will be evaluated and each resulting value will
  replace in parallel the root value of its Var.  After the body is
  executed, the root values of all the Vars will be set back to their
  old values.  These temporary changes will be visible in all threads.
  Useful for mocking out functions during testing."
  {:added "1.3"}
  [bindings & body]
  `(with-redefs-fn ~(zipmap (map #(list `var %) (take-nth 2 bindings))
                            (take-nth 2 (next bindings)))
                    (fn [] ~@body)))

;; ## Exception handling

(defn keep-going*
  "Executes the supplied fn repeatedly"
  [f on-error]
  (try
   (f)
   (catch Throwable e
     (on-error e)))
  (recur f on-error))

(defmacro keep-going
  "Executes body, repeating the execution of body even if an exception
  is thrown"
  [on-error & body]
  `(keep-going* (fn [] ~@body) ~on-error))

;; ## Unit testing

;; This is an implementation of assert-expr that works with
;; slingshot-based exceptions, so you can do:
;;
;;     (is (thrown+? <some exception> (...)))
(defmethod clojure.test/assert-expr 'thrown+? [msg form]
  (let [klass (second form)
        body (nthnext form 2)]
    `(try+ ~@body
           (clojure.test/do-report {:type :fail, :message ~msg,
                                    :expected '~form, :actual nil})
           (catch ~klass e#
             (clojure.test/do-report {:type :pass, :message ~msg,
                                      :expected '~form, :actual e#})
             e#))))

;; ## Configuration files

(defn ini-to-map
  "Takes a .ini filename and returns a nested map of
  fully-interpolated values. Strings that look like integers are
  returned as integers, and all section names and keys are returned as
  symbols."
  [filename]
  (let [ini        (Ini. (reader filename))
        m          (atom {})
        keywordize #(keyword (string/lower-case %))]

    (doseq [[name section] ini
            [key _] section
            :let [val (.fetch section key)
                  val (try
                        (Integer/parseInt val)
                        (catch NumberFormatException e val))]]
      (swap! m assoc-in [(keywordize name) (keywordize key)] val))
    @m))

;; ## Command-line parsing

(defn cli!
  "Wraps `tools.cli/cli`, automatically adding in a set of options for
  displaying help.

  If the user asks for help, we display the help banner text and the
  process will immediately exit."
  [args & specs]
  (let [specs                    (conj specs
                                       ["-h" "--help" "Show help" :default false :flag true])
        [options posargs banner] (apply cli/cli args specs)]
    (when (:help options)
      (println banner)
      (System/exit 0))
    [options posargs]))

;; ## Ring helpers

(defn acceptable-content-type
  "Returns a boolean indicating whether the `candidate` mime type
  matches any of those listed in `header`, an Accept header."
  [candidate header]
  {:pre [(string? candidate)]}
  (if-not (string? header)
    true
    (let [[prefix suffix] (.split candidate "/")
          wildcard        (str prefix "/*")
          types           (->> (clojure.string/split header #",")
                              (map #(.trim %))
                              (set))]
      (or (types wildcard)
          (types candidate)))))

(defn json-response
  "Returns a Ring response object with the supplied body and a JSON
  content type"
  [body]
  (-> body
      (json/generate-string)
      (rr/response)
      (rr/header "Content-Type" "application/json")))

(defn uri-segments
  "Converts the given URI into a seq of path segments. Empty segments
  (from a `//`, for example) are elided from the result"
  [uri]
  (remove #{""} (.split uri "/")))

