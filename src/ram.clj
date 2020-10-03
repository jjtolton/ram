(ns ram)

; state
; -----

(def empty-state {:charge-map {} :machines []})
(defn add-machine [s m] (update s :machines conj m))
(defn dependent-machines [state wire]
  (->> state
       :machines
       (filter (fn [{:keys [in]}]
                 (some #{wire} in)))))

(comment
  (dependent-machines
    (add-machine empty-state {:in [:a :b] :out :o})
    :a))

; wires
; ------

(defn wire-name [n & args]
  (keyword (apply str (list* (name n) args))))

(def _names (atom {}))
(defn wire
  ([] (wire :w))
  ([n]
   (swap! _names update n (fn [i] (inc (or i 0))))
   (let [i (@_names n)]
     (if (> i 1)
       (wire-name n "#" i)
       n))))

(defn wires [n r]
  (mapv (fn [i] (wire (wire-name n "-" i))) (range r)))

(def w# nth)

(defn read-wires [{:keys [charge-map]} ws]
  (map charge-map ws))

; nand
; ----

(defn nand-xf [a b]
  (if (= a b 1) 0 1))

(defn wire-nand-gate [state a b o]
  (add-machine state {:in [a b] :out o}))

(comment
  (wire-nand-gate empty-state :a :b :o))

; trigger
; -------

(declare trigger)

(defn trigger-machine [state {:keys [in out]}]
  (let [curr-v (get-in state [:charge-map out])
        new-v (apply nand-xf (map (:charge-map state)
                                  in))]
    (if (= curr-v new-v)
      state
      (trigger state out new-v))))

(defn trigger [state wire new-v]
  (let [state' (assoc-in state [:charge-map wire] new-v)
        machines (dependent-machines state wire)]
    (reduce (fn [s m] (trigger-machine s m)) state' machines)))

(comment
  (do
    (def s (wire-nand-gate {} :a :b :o))
    (def s' (trigger s :a 1))
    (println
      "a -> 1, :b -> 0 :o -> 1 \n"
      (read-wires s' [:a :b :o]))
    (def s'' (trigger s' :b 1))
    (println
      "a -> 1 b -> 1 o -> 0 \n"
      (read-wires s'' [:a :b :o]))))

;; not-gate

(defn wire-not-gate
  ([state a o]
   (wire-nand-gate state a a o)))

(comment
  (do
    (def s (wire-not-gate empty-state :a :o))
    (def s' (trigger s :a 0))
    (println
      "a -> 0 o -> 1 \n" (read-wires s' [:a :o]))
    (def s'' (trigger s' :a 1))
    (println
      "a -> 1 o -> 0 \n" (read-wires s'' [:a :o]))))

(defn wire-and-gate [state a b o]
  (let [nand-o (wire)]
    (-> state
        (wire-nand-gate a b nand-o)
        (wire-not-gate nand-o o))))

(comment
  (do
    (def s (wire-and-gate empty-state :a :b :o))
    (def s' (trigger s :a 1))
    s'
    (println
      "a -> 1 b -> 0 o -> 0 \n" (read-wires s' [:a :b :o]))
    (def s'' (trigger s' :b 1))
    (println
      "a -> 1 b -> 1 o -> 1 \n" (read-wires s'' [:a :b :o]))))

;; memory-bit

(defn wire-memory-bit
  "
                           a                              +------------+
        +----------+    +---------------------------------+            |         o
 i +----+          |    |                                 |            +----+-----+
        |          +----+                            +----+            |    |
        |          |    |                            |    +------------+    |
   +----+          |    |                            |                      |
   |    +----------+    |                            +-------------------------+
   |                    |                                                   |  |
   |                    |                              +--------------------+  |
   |                    |                              |                       |
   |                    |   +------------+             | +-------------+       |
   |                    +---+            |             | |             |       |
   |                        |            | b           +-+             +-------+
   |                        |            +----+          |             |  c
+--+------------------------+            |    +----------+             |
 s                          +------------+               |             |
                                                         +-------------+

  "
  ([state s i o]
   (let [a (wire)
         b (wire)
         c (wire)]
     (-> state
         (wire-nand-gate i s a)
         (wire-nand-gate a s b)
         (wire-nand-gate a c o)
         (wire-nand-gate b o c)))))

(comment
  (do
    (def s (wire-memory-bit empty-state :s :i :o))
    (def s' (trigger s :i 1))
    s'
    (println
      "b/c s -> 0 / i -> 1 o -> 0  \n"
      (read-wires s' [:s :i :o]))
    (def s'' (trigger s' :s 1))
    (println
      "b/c s-> 1 / i -> 1 o -> 1 \n"
      (read-wires s'' [:s :i :o]))
    (def s''' (trigger (trigger s'' :s 0) :i 0))
    (println
      "b/c s -> 0 / i -> 0 o -> 1 \n"
      (read-wires s''' [:s :i :o]))))

; byte
; ----

(defn wire-byte [state s ins outs]
  (reduce (fn [acc-state [i o]]
            (wire-memory-bit acc-state s i o))
          state
          (map vector ins outs)))

(comment
  (do
    (def is (wires :i 8))
    (def os (wires :o 8))
    (def s
      (wire-byte empty-state :s is os))
    (def s' (-> s
                (trigger (w# is 0) 1)
                (trigger (w# is 1) 1)
                (trigger (w# is 2) 1)))
    (println
      "change i, but do not set o yet \n"
      [(read-wires s' is)
       (read-wires s' os)])
    (def s'' (-> s' (trigger :s 1)))
    (println
      "okay, set s, so i1-3 are in os \n"
      [(read-wires s'' is)
       (read-wires s'' os)])
    (def s''' (-> s'' (trigger :s 0)))
    (println
      "okay, disable s, so os are frozen \n"
      [(read-wires s''' is)
       (read-wires s''' os)])
    (def s4 (-> s'''
                (trigger (w# is 0) 0)
                (trigger (w# is 1) 0)
                (trigger (w# is 2) 0)
                (trigger (w# is 3) 1)))
    (println
      "i4 should be 1, but only o1-3 should be 1 \n"
      [(read-wires s4 is)
       (read-wires s4 os)])))

(defn wire-enabler
  [state e ins outs]
  (reduce
    (fn [acc-state [in out]]
      (wire-and-gate acc-state e in out))
    state
    (map vector ins outs)))

(comment
  (do
    (def is (wires :i 8))
    (def os (wires :o 8))
    (def s (-> (wire-enabler empty-state :e is os)
               (trigger (w# is 0) 1)
               (trigger (w# is 1) 1)
               (trigger (w# is 2) 1)))
    (println
      "i1-3 should be 1, but os should all be 0 \n"
      [(read-wires s is)
       (read-wires s os)])
    (def s' (trigger s :e 1))
    (println
      "os should be triggered now \n"
      [(read-wires s' is)
       (read-wires s' os)])
    (def s'' (trigger s :e 0))
    (println
      "os should be blocked to 0 again \n"
      [(read-wires s'' is)
       (read-wires s'' os)])))

(defn wire-register [state s e ins bits outs]
  (-> state
      (wire-byte s ins bits)
      (wire-enabler e bits outs)))

(comment
  (do
    (def is (wires :i 8))
    (def bs (wires :b 8))
    (def os (wires :o 8))
    (def s (-> (wire-register empty-state :s :e is bs os)
               (trigger (w# is 0) 1)
               (trigger (w# is 1) 1)
               (trigger (w# is 2) 1)))
    (println
      "i1-3 should be 1, but os should all be 0 \n"
      [(read-wires s is)
       (read-wires s bs)
       (read-wires s os)])
    (def s' (trigger s :s 1))
    (println
      "b1-3 should be 1, but os should be 0 \n"
      [(read-wires s' is)
       (read-wires s' bs)
       (read-wires s' os)])
    (def s'' (trigger s' :e 1))
    (println
      "os should be 1 now \n"
      [(read-wires s'' is)
       (read-wires s'' bs)
       (read-wires s'' os)])))


(defn wire-bus [state bus-wires register-infos]
  (reduce
    (fn [acc-state [s e bits]]
      (wire-register acc-state s e bus-wires bits bus-wires))
    state
    register-infos))

;; hmm something is going wrong with the bus :thinking:
(comment
  (do
    (def bw (wires :bw 8))
    (def r1-bits (wires :r1 8))
    (def r2-bits (wires :r2 8))
    (def r3-bits (wires :r3 8))
    (def s (wire-bus empty-state
                     bus-wires
                     [[:s1 :e1 r1-bits]
                      [:s2 :e2 r2-bits]
                      [:s3 :e3 r3-bits]]))
    (def s' (-> s
                (trigger (w# r1-bits 0) 1)
                (trigger (w# r1-bits 1) 1)))
    (println
      "set r1 bits. no other reg should be affected \n"
      [(read-wires s' bw)
       (read-wires s' r1-bits)
       (read-wires s' r2-bits)
       (read-wires s' r3-bits)])
    (def s'' (-> s' (trigger :e1 1)))
    (println
      "enable r1. bus should now have the same charge \n"
      [(read-wires s'' bw)
       (read-wires s'' r1-bits)
       (read-wires s'' r2-bits)
       (read-wires s'' r3-bits)])
    (def s''' (-> s'' (trigger :s3 1) (trigger :e1 0)))
    (println
      "set r3. charge should now move to r3 \n"
      [(read-wires s'' bw)
       (read-wires s'' r1-bits)
       (read-wires s'' r2-bits)
       (read-wires s'' r3-bits)])))
