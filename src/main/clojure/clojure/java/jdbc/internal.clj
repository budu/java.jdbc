;;  Copyright (c) Stephen C. Gilardi. All rights reserved.  The use and
;;  distribution terms for this software are covered by the Eclipse Public
;;  License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can
;;  be found in the file epl-v10.html at the root of this distribution.  By
;;  using this software in any fashion, you are agreeing to be bound by the
;;  terms of this license.  You must not remove this notice, or any other,
;;  from this software.
;;
;;  internal definitions for clojure.java.jdbc
;;
;;  scgilardi (gmail)
;;  Created 3 October 2008
;;
;;  seancorfield (gmail)
;;  Migrated from clojure.contrib.sql.internal 17 April 2011

(ns clojure.java.jdbc.internal
  (:import
    (clojure.lang RT)
    (java.sql BatchUpdateException DriverManager SQLException Statement)
    (java.util Hashtable Map Properties)
    (javax.naming InitialContext Name)
    (javax.sql DataSource)))

(def ^:dynamic *db* {:connection nil :level 0})

(def ^:dynamic *stropping-fn* identity)

(def ^:dynamic *stropping-escape-fn* identity)

(def ^:dynamic *inbound-naming-strategy-fn* identity)
(def ^:dynamic *outbound-naming-strategy-fn* identity)

(def special-counts
  {Statement/EXECUTE_FAILED "EXECUTE_FAILED"
   Statement/SUCCESS_NO_INFO "SUCCESS_NO_INFO"})

(defn find-connection*
  "Returns the current database connection (or nil if there is none)"
  []
  (:connection *db*))

(defn connection*
  "Returns the current database connection (or throws if there is none)"
  []
  (or (find-connection*)
      (throw (Exception. "no current database connection"))))

(defn rollback
  "Accessor for the rollback flag on the current connection"
  ([]
    (deref (:rollback *db*)))
  ([val]
    (swap! (:rollback *db*) (fn [_] val))))

(defn- as-str
  [x]
  (if (instance? clojure.lang.Named x)
    (name x)
    (str x)))

(defn- ^Properties as-properties
  "Convert any seq of pairs to a java.utils.Properties instance.
   Uses as-str to convert both keys and values into strings."
  {:tag Properties}
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (as-str k) (as-str v)))
    p))

(defn get-connection
  "Creates a connection to a database. db-spec is a map containing values
  for one of the following parameter sets:

  Factory:
    :factory     (required) a function of one argument, a map of params
    (others)     (optional) passed to the factory function in a map

  DriverManager:
    :classname   (required) a String, the jdbc driver class name
    :subprotocol (required) a String, the jdbc subprotocol
    :subname     (required) a String, the jdbc subname
    (others)     (optional) passed to the driver as properties.

  DataSource:
    :datasource  (required) a javax.sql.DataSource
    :username    (optional) a String
    :password    (optional) a String, required if :username is supplied

  JNDI:
    :name        (required) a String or javax.naming.Name
    :environment (optional) a java.util.Map"
  [{:keys [factory
           classname subprotocol subname
           datasource username password
           name environment]
    :as db-spec}]
  (cond
    factory
    (factory (dissoc db-spec :factory))
    (and classname subprotocol subname)
    (let [url (format "jdbc:%s:%s" subprotocol subname)
          etc (dissoc db-spec :classname :subprotocol :subname)]
      (RT/loadClassForName classname)
      (DriverManager/getConnection url (as-properties etc)))
    (and datasource username password)
    (.getConnection datasource username password)
    datasource
    (.getConnection datasource)
    name
    (let [env (and environment (Hashtable. environment))
          context (InitialContext. env)
          datasource (.lookup context name)]
      (.getConnection datasource))
    :else
    (throw (IllegalArgumentException. (format "db-spec %s is missing a required parameter" db-spec)))))

(defn with-connection*
  "Evaluates func in the context of a new connection to a database then
  closes the connection."
  [db-spec func]
  (with-open [con (get-connection db-spec)]
    (binding [*db* (assoc *db* :connection con :level 0 :rollback (atom false))]
      (func))))

(defn print-sql-exception
  "Prints the contents of an SQLException to stream"
  [stream exception]
  (.println
    stream
    (format (str "%s:" \newline
                 " Message: %s" \newline
                 " SQLState: %s" \newline
                 " Error Code: %d")
            (.getSimpleName (class exception))
            (.getMessage exception)
            (.getSQLState exception)
            (.getErrorCode exception))))

(defn print-sql-exception-chain
  "Prints a chain of SQLExceptions to stream"
  [stream exception]
  (loop [e exception]
    (when e
      (print-sql-exception stream e)
      (recur (.getNextException e)))))

(defn print-update-counts
  "Prints the update counts from a BatchUpdateException to stream"
  [stream exception]
  (.println stream "Update counts:")
  (dorun 
    (map-indexed 
      (fn [index count] 
        (.println stream 
                  (format " Statement %d: %s"
                          index
                          (get special-counts count count)))) 
      (.getUpdateCounts exception))))

(defn throw-rollback
  "Sets rollback and throws a wrapped exception"
  [e]
  (rollback true)
  (throw (Exception. (format "transaction rolled back: %s" (.getMessage e)) e)))

(defn transaction*
  "Evaluates func as a transaction on the open database connection. Any
  nested transactions are absorbed into the outermost transaction. By
  default, all database updates are committed together as a group after
  evaluating the outermost body, or rolled back on any uncaught
  exception. If rollback is set within scope of the outermost transaction,
  the entire transaction will be rolled back rather than committed when
  complete."
  [func]
  (binding [*db* (update-in *db* [:level] inc)]
    (if (= (:level *db*) 1)
      (let [con (connection*)
            auto-commit (.getAutoCommit con)]
        (io!
          (.setAutoCommit con false)
          (try
            (func)
            (catch BatchUpdateException e
              (print-update-counts *err* e)
              (print-sql-exception-chain *err* e)
              (throw-rollback e))
            (catch SQLException e
              (print-sql-exception-chain *err* e)
              (throw-rollback e))
            (catch Exception e
              (throw-rollback e))
            (finally
              (if (rollback)
                (.rollback con)
                (.commit con))
              (rollback false)
              (.setAutoCommit con auto-commit)))))
      (func))))

(defn with-stropping*
  "Evaluates func so that the as-identifer function will output stropped
  identifiers."
  [chars escape-fn func]
  (let [chars (if (vector? chars)
                chars
                [chars chars])]
    (binding [*stropping-fn* #(str (first chars) % (second chars))
              *stropping-escape-fn* escape-fn]
      (func))))

(defn with-naming-strategy*
  "Evaluates func so that the given inbound and outbound naming
  strategies are applied to identifiers."
  [inbound-fn outbound-fn func]
  (binding [*inbound-naming-strategy-fn* inbound-fn
            *outbound-naming-strategy-fn* outbound-fn]
    (func)))

(defn do-prepared*
  "Executes an (optionally parameterized) SQL prepared statement on the
  open database connection. Each param-group is a seq of values for all of
  the parameters."
  [return-keys sql & param-groups]
  (with-open [stmt (if return-keys 
                     (.prepareStatement (connection*) sql java.sql.Statement/RETURN_GENERATED_KEYS)
                     (.prepareStatement (connection*) sql))]
    (doseq [param-group param-groups]
      (dorun 
        (map-indexed 
          (fn [index value] 
            (.setObject stmt (inc index) value)) 
          param-group))
      (.addBatch stmt))
    (transaction* (fn [] 
                    (let [rs (seq (.executeBatch stmt))]
                      (if return-keys (first (resultset-seq (.getGeneratedKeys stmt))) rs))))))

(defn with-query-results*
  "Executes a query, then evaluates func passing in a seq of the results as
  an argument. The first argument is a vector containing the (optionally
  parameterized) sql query string followed by values for any parameters."
  [[sql & params :as sql-params] func]
  (when-not (vector? sql-params)
    (throw (IllegalArgumentException. (format "\"%s\" expected %s %s, found %s %s"
                                              "sql-params"
                                              "vector"
                                              "[sql param*]"
                                              (.getName (class sql-params))
                                              (pr-str sql-params)))))
  (with-open [stmt (.prepareStatement (connection*) sql)]
    (dorun 
      (map-indexed
        (fn [index value] 
          (.setObject stmt (inc index) value)) 
        params))
    (with-open [rset (.executeQuery stmt)]
      (func (resultset-seq rset)))))

(defn as-identifier*
  "Returns a qualified SQL identifier built from a single or a sequence
  of keywords. When used with a naming strategy, apply the inbound
  strategy to the given identifier When used inside a with-stropping
  call, the returned identifiers will be stropped."
  [keywords]
  (let [keywords (if (keyword? keywords)
                   [keywords]
                   keywords)
        ->identifier (comp *stropping-fn*
                           *stropping-escape-fn*
                           *inbound-naming-strategy-fn*
                           as-str)]
    (->> keywords
         (map ->identifier)
         (interpose \.)
         (apply str))))
