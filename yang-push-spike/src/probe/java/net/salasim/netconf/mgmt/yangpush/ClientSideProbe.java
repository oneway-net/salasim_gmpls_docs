package net.salasim.netconf.mgmt.yangpush;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.salasim.netconf.mgmt.YangResources;
import org.opendaylight.netconf.api.messages.NetconfMessage;
import org.opendaylight.netconf.client.mdsal.api.NetconfSessionPreferences;
import org.opendaylight.netconf.client.mdsal.impl.DefaultBaseNetconfSchemaProvider;
import org.opendaylight.netconf.client.mdsal.impl.NetconfMessageTransformer;
import org.opendaylight.yangtools.databind.DatabindContext;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.codec.xml.XmlParserStream;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizationResultHolder;
import org.opendaylight.yangtools.yang.model.util.SchemaInferenceStack;
import org.xml.sax.InputSource;

/**
 * Phase 0.3 spike, client side: what ODL's netconf client (the code under the controller's mounts) does with the
 * RFC 8639/8641 messages. Uses the same {@code NetconfMessageTransformer} the mount's NetconfDevice uses for every
 * RPC request and every notification, against the schema context that includes the push modules.
 */
public final class ClientSideProbe {

    static final String SN = YangPushPrototype.SN_NS;
    static final String YP = YangPushPrototype.YP_NS;
    static final String IF = "urn:ietf:params:xml:ns:yang:ietf-interfaces";

    public static void main(String[] args) throws Exception {
        var models = YangResources.load();
        var ctx = models.context();
        var base = new DefaultBaseNetconfSchemaProvider(YangResources.PARSER_FACTORY).baseSchemaForCapabilities(
            NetconfSessionPreferences.fromStrings(List.of("urn:ietf:params:netconf:base:1.1",
                "urn:ietf:params:netconf:capability:notification:2.0")));
        var transformer = new NetconfMessageTransformer(DatabindContext.ofModel(ctx), true, base);

        // 0. every notification the server prototype emitted during YangPushPrototypeTest, parsed by the client
        var captured = java.nio.file.Path.of(args.length > 0 ? args[0] : "target/captured-notifications.txt");
        int ok = 0;
        int bad = 0;
        if (java.nio.file.Files.exists(captured)) {
            for (String line : java.nio.file.Files.readAllLines(captured)) {
                try {
                    var event = transformer.toNotification(new NetconfMessage(doc(line.replace("&#10;", "\n"))));
                    ok++;
                    if (ok <= 3) {
                        System.out.println("CLIENT-CAPTURED-PARSED " + event.getBody());
                    }
                } catch (Throwable t) {
                    bad++;
                    System.out.println("CLIENT-CAPTURED-FAILED " + t + " <- " + line);
                }
            }
        }
        System.out.println("CLIENT-CAPTURED-SUMMARY parsed=" + ok + " failed=" + bad);

        // 1. a push-change-update exactly as the server prototype emits it (taken from a test run)
        String push = "<notification xmlns=\"urn:ietf:params:xml:ns:netconf:notification:1.0\">"
            + "<push-change-update xmlns=\"" + YP + "\"><id>7</id><datastore-changes>"
            + "<yang-patch><patch-id>1</patch-id>"
            + "<edit><edit-id>1</edit-id><operation>replace</operation>"
            + "<target>/ietf-interfaces:interfaces/interface=10.0.0.1%7C10.0.0.2/oper-status</target>"
            + "<value><oper-status xmlns=\"" + IF + "\">down</oper-status></value>\n</edit>"
            + "<edit><edit-id>2</edit-id><operation>delete</operation>"
            + "<target>/ietf-interfaces:interfaces/interface=x/oper-status</target></edit>"
            + "</yang-patch></datastore-changes></push-change-update>"
            + "<eventTime>2026-10-05T13:21:30.669862Z</eventTime></notification>";
        try {
            var event = transformer.toNotification(new NetconfMessage(doc(push)));
            System.out.println("CLIENT-PUSH-PARSED " + event.getBody());
        } catch (Throwable t) {
            System.out.println("CLIENT-PUSH-FAILED " + t);
            t.printStackTrace(System.out);
        }

        // 2. subscription-terminated
        String term = "<notification xmlns=\"urn:ietf:params:xml:ns:netconf:notification:1.0\">"
            + "<subscription-terminated xmlns=\"" + SN + "\"><id>7</id><reason>no-such-subscription</reason>"
            + "</subscription-terminated><eventTime>2026-10-05T13:21:30.669862Z</eventTime></notification>";
        try {
            var event = transformer.toNotification(new NetconfMessage(doc(term)));
            System.out.println("CLIENT-TERMINATED-PARSED " + event.getBody());
        } catch (Throwable t) {
            System.out.println("CLIENT-TERMINATED-FAILED " + t);
        }

        // 3. establish-subscription request: parse the XML into the typed RPC input, then let the client render it
        for (var variant : List.of("xpath", "subtree")) {
            String xml = variant.equals("xpath")
                ? "<input xmlns=\"" + SN + "\"><datastore xmlns=\"" + YP + "\" xmlns:ds=\"urn:ietf:params:xml:ns:yang:ietf-datastores\">ds:operational</datastore>"
                    + "<datastore-xpath-filter xmlns=\"" + YP + "\" xmlns:if=\"" + IF + "\">/if:interfaces/if:interface/if:oper-status</datastore-xpath-filter>"
                    + "<on-change xmlns=\"" + YP + "\"><dampening-period>0</dampening-period></on-change></input>"
                : "<input xmlns=\"" + SN + "\"><datastore xmlns=\"" + YP + "\" xmlns:ds=\"urn:ietf:params:xml:ns:yang:ietf-datastores\">ds:operational</datastore>"
                    + "<datastore-subtree-filter xmlns=\"" + YP + "\"><interfaces xmlns=\"" + IF + "\"><interface><oper-status/></interface></interfaces></datastore-subtree-filter>\n"
                    + "<on-change xmlns=\"" + YP + "\"><dampening-period>0</dampening-period></on-change></input>";
            try {
                var rpc = QName.create(SN, "2019-09-09", "establish-subscription");
                var stack = SchemaInferenceStack.of(ctx);
                stack.enterSchemaTree(rpc);
                stack.enterSchemaTree(QName.create(rpc, "input"));
                var result = new NormalizationResultHolder();
                var writer = ImmutableNormalizedNodeStreamWriter.from(result);
                // the DOM route the controller's client itself uses (NetconfMessageTransformer): traverse(DOMSource)
                XmlParserStream.create(writer, stack.toInference()).traverse(
                    new DOMSource(doc(xml).getDocumentElement()));
                var input = (ContainerNode) result.getResult().data();
                System.out.println("CLIENT-ESTABLISH-INPUT-PARSED[" + variant + "] " + input);
                NetconfMessage request = transformer.toRpcRequest(rpc, input);
                System.out.println("CLIENT-ESTABLISH-REQUEST[" + variant + "] " + serialize(request.getDocument()));
            } catch (Throwable t) {
                System.out.println("CLIENT-ESTABLISH-FAILED[" + variant + "] " + t);
                t.printStackTrace(System.out);
            }
        }
    }

    static org.w3c.dom.Document doc(String xml) throws Exception {
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    static String serialize(org.w3c.dom.Node n) throws Exception {
        var out = new StringWriter();
        var t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.transform(new DOMSource(n), new StreamResult(out));
        return out.toString();
    }
}
