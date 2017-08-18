(ns lupapalvelu.ui.printing-order.state)

(def empty-component-state {:attachments []
                            :tagGroups   []
                            :order       {}
                            :contacts    {:payer-same-as-orderer true
                                          :delivery-same-as-orderer true
                                          :orderer {}
                                          :payer {}
                                          :delivery {}}
                            :billingReference ""
                            :deliveryInstructions ""
                            :phase       1
                            :id          nil})

(defonce component-state (atom empty-component-state))

(defn will-unmount [& _]
  (reset! component-state empty-component-state))

(defn proceed-phase2 []
  (swap! component-state assoc :phase 2))

(defn back-to-phase1 []
  (swap! component-state assoc :phase 1))

(defn proceed-phase3 []
  (swap! component-state assoc :phase 3))