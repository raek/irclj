(ns irclj.irclj
  "A small IRC library to abstract over lower-level IRC connection handling."
  {:author "Anthony Simpson (Rayne)"}
  (:use [clojure.string :only [join]]
        [clojure.contrib.def :only [defmacro-]])
  (:require [clojure.java.io :as io])
  (:import (java.io PrintWriter)
           (java.net Socket)))

(defrecord IRC [name password server username port realname fnmap])

;; Other specialized encodings (e.g. for cyrillic or latin characters with diacritics)
;; might actually still be the more common ones, but UTF-8 works/breaks equally for
;; everyone and could one day become the single standard encoding.
(def default-encoding "UTF-8")

(defn create-irc 
  "Function to create an IRC(bot). You need to at most supply a server and fnmap.
  If you don't supply a name, username, realname, or port, they will default to
  irclj, irclj, teh bawt, and 6667 respectively."
  [{:keys [name password server username port realname fnmap]
    :or {name "irclj" username "irclj" realname "teh bawt"
         port 6667}}]
  (IRC. name password server username port realname fnmap))

(defn print-irc-line
  "Prints a line of text to an IRC connection."
  [{{sockout :sockout} :connection} text]
  (println (str ">>>" text))
  (binding [*out* sockout] 
    (println text)
    (str sockout)))

(defn read-irc-line
  "Reads a line from IRC. Returns the string 'Socket Closed.' if the socket provided is closed."
  [{{sockin :sockin} :connection}]
  (try 
    (binding [*in* sockin]
      (let [line (read-line)]
        (println line)
        line))
    (catch java.net.SocketException _ "Socket Closed.")))

(defn- strip-start
  "Strips everything away until the message."
  [s]
  (second (.split s ":")))

(defn send-msg
  "Utility function for sending raw messages. Don't use this function unless you're implementing
  a protocol function. Use send-message instead."
  [irc type message]
  (print-irc-line @irc (str type " " message)))

(defn send-raw-message
  "Takes an IRC, a message and a target to send it to, and sends an IRC message to
  target (user, channel). Be careful using this function, because newlines and carriage
  returns are filter out. You want to use send-message unless you need this behavior."
  [irc target message]
  (send-msg irc "PRIVMSG" (str target " :" message)))

(defn send-message
  "Takes an IRC, a message and a target to send it to, and sends an IRC message to
  target (user, channel). Filters out newlines and carriage returns."
  [irc target message]
  (when (seq message)
    (send-msg irc "PRIVMSG" (str target " :" (.replaceAll message "\n|\r" "")))))

(defn send-notice
  "Takes an IRC, a message, and a target to send to, and sends a NOTICE to target
  (user, channel)."
  [irc target message]
  (when (seq message)
    (send-msg irc "NOTICE" (str target " :" message))))

(defn send-action
  "Sends a CTCP ACTION to a target (user, channel)"
  [irc target message]
  (when (seq message)
    (send-msg irc "PRIVMSG" (str target " :" \u0001 "ACTION " message \u0001))))

(defn set-nick
  "Changes your nick."
  [irc nick]
  (let [res (send-msg irc "NICK" nick)]
    (when (= (second (.split (read-irc-line @irc) " ")) "NICK")
      (dosync (alter irc assoc :name nick)))
    res))

(defn- rest-irc-line
  "Returns a normal line from IRC without the colon."
  [irc] (apply str (rest (read-irc-line @irc))))

(def legal-symbols {"+o" \@ "+h" \% "+a" \& "+v" \+ "+q" \~})

(defn- parse-users
  "Parses modes from user names."
  [& users]
  (let [legal-symbols (zipmap (vals legal-symbols) (keys legal-symbols))]
    (into {} (for [user users]
               (if (legal-symbols (first user))
                 [(apply str (rest user)) {:mode (legal-symbols (first user))}]
                 [user {:mode :none}])))))

(defn join-chan
  "Joins a channel."
  [irc channel]
  (when-not (some #(= % channel) (:channels @irc))
    (let [res (send-msg irc "JOIN" (str ":" channel))]
      (loop [line (rest-irc-line irc)]
        (let [[_ n & more] (.split (apply str (remove #(= \: %) line)) " ")]
          (condp = n
              "332" (do
                      (dosync (alter irc assoc-in
                                     [:channels (nth more 1) :topic]
                                     (apply str (interpose " " (drop 2 more)))))
                      (recur (rest-irc-line irc)))
              "353" (do
                      (dosync
                       (alter irc update-in [:channels (nth more 2) :users]
                              #(into (if % % {}) %2) (->> more (drop 3) (apply parse-users))))
                      (recur (rest-irc-line irc)))
              "366" nil
              "403" nil
              (recur (rest-irc-line irc))))))))

(defn part-chan
  "Leaves a channel."
  [irc channel & {reason :reason}]
  (let [res (send-msg irc "PART" channel)]
    (dosync (alter irc assoc :channels (remove #(= % channel) (:channels @irc))))
    res))

(defn set-mode
  "Set modes."
  [irc channel mode nick]
  (send-msg irc "MODE" (str channel " " mode " " nick)))

(defn set-topic
  "Sets the topic for a channel."
  [irc channel topic]
  (send-msg irc "TOPIC" (str channel " :" topic)))

(defn kick
  "Kicks a user from a channel."
  [irc channel nick & {reason :reason :or [reason "bai"]}]
  (send-msg irc "KICK" (str channel " " nick " :" reason)))

(defn get-names
  "Gets a list of the users in a channel. Includes modes. Returns nil if the channel
  doesn't exist."
  [irc channel]
  (send-msg irc "NAMES" channel)
  (loop [acc []]
    (let [rline (apply str (rest (read-irc-line @irc)))
          words (.split rline " ")
          num (second words)]
      (when-not (= num "403")
        (if (= num "353")
          (recur (conj acc (strip-start rline))) 
          (.split (apply str (interpose " " acc)) " "))))))

(defn get-topic
  "Gets the topic of a channel. Returns a map of :topic, :set-by, and :date
  (haven't quite worked date out yet). If the channel doesn't exist, returns nil."
  [irc channel]
  (send-msg irc "TOPIC" channel)
  (let [rline (apply str (rest (read-irc-line @irc)))
        words (.split rline " ")]
    (when (= (second words) "332")
      (let [rline2 (.split (read-irc-line irc) " ")]
        {:topic (apply str (rest (drop-while #(not= % \:) rline)))
         :set-by (last (butlast rline2))
         :date (last rline2)}))))

(defn whois
  "Sends a whois request and returns a map with the contents mapped to keys.
  Keys are: :loggedinas, :user, :channels, and :server. Returns nil if user
  doesn't exist."
  [irc nick]
  (send-msg irc "WHOIS" nick)
  (loop [acc []]
    (let [rline (apply str (rest (read-irc-line @irc)))
          words (.split rline " ")
          num (second words)]
      (when-not (= num "401")
        (if-not (= num "318")
          (recur (->> words (drop 4) (interpose " ") (apply str) (conj acc)))
          (zipmap [:user :channels :server :loggedinas] acc))))))

(defn identify
  "Indentifies with the network."
  [irc]
  (send-message irc "NickServ" (str "IDENTIFY " (:password @irc))))

(defn- extract-message [s]
  (apply str (rest (join " " s))))

(defn- extract-channels
  "Extracts all of the channels a user was in before he quit."
  [irc user]
  (for [[channel map] (:channels @irc) :when ((:users map) user)]
    channel))

(defn- mess-to-map
  "Parses a message into a map."
  [[user doing & [channel & message :as more] :as everything] irc]
  (let [[nick ident hostmask] (.split user "\\!|\\@")
        message-map {:user user
                     :nick nick
                     :hmask hostmask
                     :ident ident
                     :doing doing}]
    (merge message-map
           {:raw-message (str ":" (apply str (interpose " " everything)))}
           (condp = doing
               "PRIVMSG" {:channel channel :message (extract-message message)}
               "QUIT" {:reason (extract-message more)}
               "JOIN" {:channel (apply str (rest channel))}
               "PART" {:channel channel :reason (extract-message message)}
               "NOTICE" {:target channel :message (extract-message message)}
               "MODE" (let [[mode target] message] {:channel channel :mode mode :target target})
               "TOPIC" {:channel channel :topic (extract-message message)}
               "KICK" (let [[target & message] message] 
                        {:channel channel :target target :message (extract-message message)})
               {}))))

(defmacro- when-not-nil 
  "Like when-not, but checks if it's predicate is nil."
  [pred & body]
  `(when-not (nil? ~pred) ~@body))

(defn- handle-ctcp
  "Takes a CTCP message and responds to it."
  [irc nick ctcp-s]
  (let [ctcp (apply str (remove #(= \u0001 %) ctcp-s))
        first-part (first (.split ctcp " "))]
    (send-notice 
     irc nick (condp = first-part
                  "VERSION" "irclj version ohai"
                  "TIME"    "Time for you to SHUT THE FUCK UP."
                  "FINGER"  "OMG, DADDY TOUCHED ME IN THE BAD PLACE.!"
                  "PING"    "PONG!"
                  "MY"      ""
                  "ACTION"  ""
                  ""))))

(defn- channel-or-nick [{:keys [channel nick irc] :as info-map}]
  (if (= channel (:name @irc)) (assoc info-map :channel nick) info-map))

(defn- remove-nick [irc nick channel]
  (dosync (alter irc update-in [:channels channel :users] dissoc nick)))

(defmulti handle (fn [irc fnm] (:doing irc)))

(defmethod handle "PRIVMSG" [{:keys [nick message irc] :as info-map} {:keys [on-message on-action]}]
  (cond
   (and on-action (.startsWith message "\u0001ACTION"))
   (on-action
    (channel-or-nick
     (->> :message info-map (drop 8) butlast (apply str) (assoc info-map :message))))
   (and (= (first message) \u0001)) (handle-ctcp irc nick message)
   :else (when-not-nil on-message (on-message (channel-or-nick info-map)))))

(defmethod handle "QUIT" [{:keys [nick irc] :as info-map} {on-quit :on-quit}]
  (let [channels (extract-channels irc nick)]
    (doseq [chan channels]
      (remove-nick irc nick chan))
    (when-not-nil on-quit (on-quit (assoc info-map :channels channels)))))

(defmethod handle "JOIN" [{:keys [nick channel irc] :as info-map} {on-join :on-join}]
  (do
    (dosync (alter irc update-in [:channels channel :users] merge (parse-users nick)))
    (when-not-nil on-join (on-join info-map))))

(defmethod handle "PART" [{:keys [nick irc channel] :as info-map} {on-part :on-part}]
  (do
    (if (= nick (:name @irc))
      (do (remove-nick irc nick channel)))
    (when-not-nil on-part (on-part info-map))))

(defmethod handle "NOTICE" [info-map {on-notice :on-notice}]
  (when-not-nil on-notice (on-notice info-map)))

(defmethod handle "MODE" [info-map {on-mode :on-mode}]
  (when-not-nil on-mode (on-mode info-map)))

(defmethod handle "TOPIC" [{:keys [irc channel topic] :as info-map} {on-topic :on-topic}]
  (do
    (dosync (alter irc assoc-in [:channels channel :topic] topic))
    (when-not-nil on-topic (on-topic info-map))))

(defmethod handle "KICK" [{:keys [irc target channel] :as info-map} {on-kick :on-kick}]
  (do
    (remove-nick irc target channel)
    (when-not-nil on-kick (on-kick info-map))))

(defmethod handle :default [& _] nil)

(defn- handle-events
  "Handles various IRC things. This is important."
  [info irc]
  (let [{{:keys [on-any]} :fnmap} @irc
        info-map (assoc info :irc irc)]
                                        ; This will be executed independent of what type of event comes in. Great for logging.
    (when-not-nil on-any (on-any info-map))
    (handle info-map (:fnmap @irc))))

(defn close
  "Closes an IRC connection (including the socket)."
  [irc]
  (let [{{:keys [sock sockout sockin]} :connection} @irc]
    (print-irc-line @irc "QUIT")
    (.close sock)
    (.close sockin)
    (.close sockout)))

(defn connect
  "Takes an IRC defrecord and optionally, a sequence of channels to join and
  connects to IRC based on the information provided in the IRC and optionally joins
  the channels. The connection itself runs in a separate thread, and the input stream
  and output stream are merged into the IRC and returned as a ref.

  If you wish to identify after connecting, you'll want to supply a password to your IRC
  record, and the optional key :identify-after-secs to connect. :indentify-after-secs takes
  a number of seconds to wait after identifying before joining channels. This is useful for
  people with hostmasks.

  Connection encoding can be specified with :encoding, which defaults to UTF-8. Separate
  encodings for input and putput can be choosen with :in-encoding and :out-encoding, which
  both default to the value of :encoding."
  [^IRC {:keys [name password server username port realname fnmap] :as botmap}
   & {:keys [channels identify-after-secs encoding out-encoding in-encoding]}]
  (let [encoding (or encoding default-encoding)
        out-encoding (or out-encoding encoding)
        in-encoding (or in-encoding encoding)
        sock (Socket. server port)
        sockout (PrintWriter. (io/writer sock :encoding out-encoding) true)
        sockin (io/reader sock :encoding in-encoding)
        irc (ref (assoc botmap :connection {:sock sock :sockin sockin :sockout sockout}))]
    (future
      (print-irc-line @irc (str "NICK " name))
      (print-irc-line @irc (str "USER " username " na na :" realname))
      (while (not (.isClosed sock))
        (let [rline (read-irc-line @irc)
              line (apply str (rest rline))
              words (.split line " ")]
          (cond
           (.startsWith rline "PING")    ; :>> 
           (do 
             (print-irc-line @irc (.replace rline "PING" "PONG")))
           (= (second words) "001")      ; :>>
           (do
             (when (and identify-after-secs password)
               (identify irc)
               (Thread/sleep (* 1000 identify-after-secs))
               (println "Sleeping while identification takes place."))
             (when channels
               (doseq [channel channels] 
                 (join-chan irc channel)))))
          :else (handle-events (mess-to-map words irc) irc))))
    irc))
