package net.salasim.probe;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;

public final class ValidationProbe {
    static String good(String body) {
        return "<prepare-run xmlns=\"urn:salasim:pce-run\">" + body + "</prepare-run>";
    }
    static final String ID = "<run-id>dep-run-1</run-id><simulator-run-id>simrun-0123456789ab</simulator-run-id><deployment-id>dep</deployment-id><domain-id>sat-1</domain-id>";
    static final String HOR = "<sim-anchor-time>2026-07-04T05:43:00.000Z</sim-anchor-time><result-slice-count>20</result-slice-count><horizon-sim-time>2026-07-04T07:23:00.000Z</horizon-sim-time>";
    static final String FS = "<frame-schedule><schedule-file>/var/x.json</schedule-file></frame-schedule>";
    static final String REUSE = "<end-to-end-reuse><selection-mode>SEEDED_RANDOM</selection-mode><random-seed>1</random-seed><retain-ratio>0.5</retain-ratio><max-idle-entries>1</max-idle-entries><retain-ttl-slices>1</retain-ttl-slices><max-idle-bandwidth-bps>0</max-idle-bandwidth-bps><bandwidth-match>EXACT</bandwidth-match><objective-match>STRICT</objective-match><idle-reuse-for-recovery>true</idle-reuse-for-recovery></end-to-end-reuse>";
    static final String CURVE = "<initial-delay-slices>1</initial-delay-slices><initial-delay-rounds>1</initial-delay-rounds><maximum-delay-slices>4</maximum-delay-slices>";
    static String cfg(String routingExtra, String digest) {
        return "<domain-runtime-config><config-digest>" + digest + "</config-digest><routing-search><boundary-search-strategy>FULL_COST</boundary-search-strategy><compute-timeout-ms>16000</compute-timeout-ms><max-child-request-count-per-tunnel>96</max-child-request-count-per-tunnel><max-precompute-child-request-count-per-tunnel>48</max-precompute-child-request-count-per-tunnel><max-candidate-path-count>64</max-candidate-path-count><post-success-lookahead>3</post-success-lookahead>" + routingExtra + "</routing-search>"
          + "<precomputation><enabled>false</enabled><apply-enabled>false</apply-enabled><stability-window-frames>3</stability-window-frames><compute-parallelism>8</compute-parallelism><compute-queue-capacity>256</compute-queue-capacity><apply-parallelism>8</apply-parallelism><apply-queue-capacity>512</apply-queue-capacity><cache-retention-slices>0</cache-retention-slices><hysteresis-min-hop-improvement>0</hysteresis-min-hop-improvement></precomputation>"
          + "<recovery><transient>" + CURVE + "</transient><structural>" + CURVE + "</structural></recovery></domain-runtime-config>";
    }
    static final String DG = "sha256:" + "a".repeat(64);

    public static void main(String[] a) throws Exception {
        final var repo = Path.of(System.getProperty("yang.repo")).toAbsolutePath();
        final var models = YangModels.load(repo);
        try (var server = new EmbeddedNetconfServer(models)) {
            { var router = server.newLocalRouter(); var c = new Local(router);
                String base = ID + HOR + FS + REUSE;
                String[][] cases = {
                    {"valid", good(base + cfg("", DG))},
                    {"unknown leaf in routing-search", good(base + cfg("<bogus-knob>1</bogus-knob>", DG))},
                    {"unknown top-level leaf", good(base + cfg("", DG) + "<serviceType>x</serviceType>")},
                    {"out-of-range (uint8 bundle 40 -> use result-slice 0)", good(ID + HOR.replace("<result-slice-count>20","<result-slice-count>0") + FS + REUSE + cfg("", DG))},
                    {"bad pattern digest", good(base + cfg("", "md5:zz"))},
                    {"bad enum", good(base + cfg("", DG).replace("FULL_COST","FASTEST"))},
                    {"bad identifier", good(base.replace("sat-1","bad id!") + cfg("", DG))},
                    {"out-of-range uint8 (compute-parallelism 200)", good(base + cfg("", DG).replace("<compute-parallelism>8","<compute-parallelism>200"))},
                    {"range 0..max: transient initial-delay-slices 0", good(base + cfg("", DG).replace("<initial-delay-slices>1</initial-delay-slices><initial-delay-rounds>1</initial-delay-rounds><maximum-delay-slices>4</maximum-delay-slices></transient>","<initial-delay-slices>0</initial-delay-slices><initial-delay-rounds>1</initial-delay-rounds><maximum-delay-slices>4</maximum-delay-slices></transient>"))},
                    {"missing mandatory run-id", good(base.replace("<run-id>dep-run-1</run-id>","") + cfg("", DG))},
                    {"missing mandatory leaf deep (compute-timeout-ms)", good(base + cfg("", DG).replace("<compute-timeout-ms>16000</compute-timeout-ms>",""))},
                    {"missing mandatory choice (no runtime config)", good(base)},
                    {"missing frame-schedule", good(ID + HOR + REUSE + cfg("", DG))},
                    {"frame-schedule empty (no schedule-file)", good(ID + HOR + "<frame-schedule/>" + REUSE + cfg("", DG))},
                    {"must violated: max precompute > max reactive", good(base + cfg("", DG).replace("<max-precompute-child-request-count-per-tunnel>48","<max-precompute-child-request-count-per-tunnel>99"))},
                    {"must violated in range: transient max-delay 1 < initial 5", good(base + cfg("", DG).replace("<initial-delay-slices>1</initial-delay-slices><initial-delay-rounds>1</initial-delay-rounds><maximum-delay-slices>4</maximum-delay-slices></transient>","<initial-delay-slices>5</initial-delay-slices><initial-delay-rounds>1</initial-delay-rounds><maximum-delay-slices>1</maximum-delay-slices></transient>"))},
                    {"duplicate leaf", good(base + cfg("", DG).replace("<compute-timeout-ms>16000</compute-timeout-ms>","<compute-timeout-ms>16000</compute-timeout-ms><compute-timeout-ms>1</compute-timeout-ms>"))},
                    {"uint64 as number 0", good(base.replace("<max-idle-bandwidth-bps>0","<max-idle-bandwidth-bps>0"))},
                    {"not a number", good(base + cfg("", DG).replace("<compute-timeout-ms>16000","<compute-timeout-ms>abc"))},
                    {"must violated: maximum-delay < initial", good(base + cfg("", DG).replace("<maximum-delay-slices>4</maximum-delay-slices></transient>","<maximum-delay-slices>0</maximum-delay-slices></transient>"))},
                    {"both choice cases", good(base + cfg("", DG) + cfg("", DG).replace("domain-runtime-config","parent-runtime-config"))},
                };
                for (var t : cases) {
                    String r; try { r = c.rpc(t[1]); } catch (Throwable e) { Throwable x = e; StringBuilder sb = new StringBuilder("EXCEPTION "); if (e instanceof org.opendaylight.netconf.api.DocumentedException de) sb.append("[tag=" + de.getErrorTag() + " type=" + de.getErrorType() + " sev=" + de.getErrorSeverity() + "] "); while (x != null) { sb.append(x.getClass().getSimpleName()).append(": ").append(x.getMessage()).append(" <- "); x = x.getCause(); } r = sb.toString(); }
                    String cl = r.replaceAll("\\s+", " ").replaceAll("xmlns=\"[^\"]*\"", ""); System.out.println("CASE " + t[0] + "\n  -> " + cl.substring(0, Math.min(650, cl.length())));
                }
            }
        }
        System.exit(0);
    }
    static final class Local {
        final org.opendaylight.netconf.server.osgi.NetconfOperationRouter router; int id;
        Local(org.opendaylight.netconf.server.osgi.NetconfOperationRouter r) { router = r; }
        String rpc(String body) throws Exception {
            String xml = "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"" + (++id) + "\">" + body + "</rpc>";
            var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance(); dbf.setNamespaceAware(true);
            var req = dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            var reply = router.onNetconfMessage(req, null);
            var out = new java.io.StringWriter();
            var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.transform(new javax.xml.transform.dom.DOMSource(reply), new javax.xml.transform.stream.StreamResult(out));
            return out.toString();
        }
    }
}
