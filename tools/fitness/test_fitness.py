"""Unit tests of the fitness matchers (what counts as a violation and what must not)."""
import unittest

import fitness


class F4Vocabulary(unittest.TestCase):
    def test_simulation_symbols_count(self):
        for ident in ["SimFramesHandler", "simTimeMs", "sim_time", "effective-sim-time", "runId", "simulator-run-id",
                      "frameIndex", "FutureFrameTLV", "speedup", "SIM_NS", "lookahead-frames", "sim"]:
            self.assertIsNotNone(fitness.f4_hit(ident), ident)

    def test_unrelated_words_do_not_count(self):
        for ident in ["SIMPLE_NETWORK", "simple", "similar", "Simplex", "ethernetFrame", "networkSlice", "frame",
                      "runtime", "rerun", "SIMD"]:
            self.assertIsNone(fitness.f4_hit(ident), ident)


class Stripping(unittest.TestCase):
    def test_java_comments_go_strings_stay(self):
        src = 'int a; // runId\n/* speedup\n */ String s = "simulationTimeMs";\n'
        out = fitness.strip_java_comments(src)
        self.assertNotIn("runId", out)
        self.assertNotIn("speedup", out)
        self.assertIn('"simulationTimeMs"', out)
        self.assertEqual(src.count("\n"), out.count("\n"))

    def test_yang_descriptions_go(self):
        out = fitness.strip_yang_strings('leaf run-id {\n  description "the sim-time\n of a run";\n}\n')
        self.assertIn("run-id", out)
        self.assertNotIn("sim-time", out)


class F7F8Patterns(unittest.TestCase):
    def test_wall_clock_calls(self):
        self.assertTrue(fitness.F7_CALLS.search("long t = System.currentTimeMillis();"))
        self.assertTrue(fitness.F7_CALLS.search("var n = Instant.now();"))
        self.assertFalse(fitness.F7_CALLS.search("long t = clock.millis();"))
        self.assertFalse(fitness.F7_CALLS.search("long t = System.nanoTime();"))

    def test_static_state(self):
        self.assertTrue(fitness.F8_STATIC_NONFINAL.match("    private static int counter = 0;"))
        self.assertTrue(fitness.F8_STATIC_NONFINAL.match("public static Map<String, X> cache;"))
        self.assertFalse(fitness.F8_STATIC_NONFINAL.match("    private static final int MAX = 3;"))
        self.assertFalse(fitness.F8_STATIC_NONFINAL.match("    public static void main(String[] a) {"))
        self.assertFalse(fitness.F8_STATIC_NONFINAL.match("    static class Inner {"))
        self.assertTrue(fitness.F8_STATIC_MUTABLE_FINAL.search("static final Map<A, B> M = new ConcurrentHashMap<>();"))
        self.assertFalse(fitness.F8_STATIC_MUTABLE_FINAL.search("static final List<A> L = List.of();"))


if __name__ == "__main__":
    unittest.main()
