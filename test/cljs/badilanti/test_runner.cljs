(ns badilanti.test-runner
  (:require
   [doo.runner :refer-macros [doo-tests]]
   [badilanti.core-test]
   [badilanti.common-test]))

(enable-console-print!)

(doo-tests 'badilanti.core-test
           'badilanti.common-test)
