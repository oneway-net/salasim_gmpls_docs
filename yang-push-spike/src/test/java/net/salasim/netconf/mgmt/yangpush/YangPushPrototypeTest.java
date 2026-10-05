package net.salasim.netconf.mgmt.yangpush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.salasim.netconf.mgmt.NetconfManagementServer;
import net.salasim.netconf.mgmt.YangResources;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.netconf.api.messages.NotificationMessage;
import org.opendaylight.netconf.server.osgi.NetconfOperationRouter;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.spi.node.ImmutableNodes;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

/**
 * Phase 0.3 spike, server side: an RFC 8639/8641 on-change publisher inside the shared NETCONF server, driven through
 * the real ODL operation router (no sockets).
 */
public class YangPushPrototypeTest {

    static final String IF_NS = "urn:ietf:params:xml:ns:yang:ietf-interfaces";
    static final String IF_REV = "2018-02-20";
    static final QName INTERFACES = QName.create(IF_NS, IF_REV, "interfaces");
    static final QName INTERFACE = QName.create(IF_NS, IF_REV, "interface");
    static final QName NAME = QName.create(IF_NS, IF_REV, "name");
    static final QName OPER_STATUS = QName.create(IF_NS, IF_REV, "oper-status");
    static final QName ADMIN_STATUS = QName.create(IF_NS, IF_REV, "admin-status");

    static NetconfManagementServer server;
    static YangResources models;
    static YangPushPrototype yp;
    static ScheduledExecutorService timer;
    static volatile List<NotificationMessage> currentInbox;
    static final List<String> LOG = new CopyOnWriteArrayList<>();

    @BeforeClass
    public static void start() throws Exception {
        models = YangResources.load();
        server = new NetconfManagementServer(models, "yp-test", 1, LoggerFactory.getLogger("test"));
        timer = Executors.newSingleThreadScheduledExecutor();
        yp = new YangPushPrototype(server.dataBroker(), models::context, (id, session) -> {
            var inbox = currentInbox;
            return inbox::add;
        }, timer, Set.of(List.of(INTERFACES, INTERFACE, OPER_STATUS)));
        server.addOperationServiceFactory(yp);
    }

    @org.junit.After
    public void closeSubscriptions() {
        yp.close(); // no subscription outlives its test (one shared operational datastore)
    }

    @AfterClass
    public static void stop() {
        yp.close();
        timer.shutdownNow();
        server.close();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    static String rpc(NetconfOperationRouter router, String body) throws Exception {
        String xml = "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"1\">" + body + "</rpc>";
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        var reply = router.onNetconfMessage(dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml))), null);
        return serialize(reply);
    }

    static String serialize(org.w3c.dom.Node n) throws Exception {
        var out = new StringWriter();
        var t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.transform(new DOMSource(n), new StreamResult(out));
        return out.toString();
    }

    static String establishXPath(String xpath, int dampeningCs) {
        return establishXPath(xpath, dampeningCs, false);
    }

    static String establishXPath(String xpath, int dampeningCs, boolean syncOnStart) {
        return "<establish-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\">"
            + "<datastore xmlns=\"" + YangPushPrototype.YP_NS + "\" xmlns:ds=\"" + YangPushPrototype.DS_NS
            + "\">ds:operational</datastore>"
            + "<datastore-xpath-filter xmlns=\"" + YangPushPrototype.YP_NS + "\" xmlns:if=\"" + IF_NS + "\">" + xpath
            + "</datastore-xpath-filter>"
            + "<on-change xmlns=\"" + YangPushPrototype.YP_NS + "\"><dampening-period>" + dampeningCs
            + "</dampening-period><sync-on-start>" + syncOnStart + "</sync-on-start></on-change></establish-subscription>";
    }

    static String establishSubtree(int dampeningCs) {
        return "<establish-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\">"
            + "<datastore xmlns=\"" + YangPushPrototype.YP_NS + "\" xmlns:ds=\"" + YangPushPrototype.DS_NS
            + "\">ds:operational</datastore>"
            + "<datastore-subtree-filter xmlns=\"" + YangPushPrototype.YP_NS + "\"><interfaces xmlns=\"" + IF_NS
            + "\"><interface><oper-status/></interface></interfaces></datastore-subtree-filter>"
            + "<on-change xmlns=\"" + YangPushPrototype.YP_NS + "\"><dampening-period>" + dampeningCs
            + "</dampening-period><sync-on-start>false</sync-on-start></on-change></establish-subscription>";
    }

    static long idOf(String reply) {
        var m = java.util.regex.Pattern.compile("<id[^>]*>(\\d+)</id>").matcher(reply);
        assertTrue(reply, m.find());
        return Long.parseLong(m.group(1));
    }

    static void operStatus(String name, String status) throws Exception {
        var tx = server.dataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.builder().node(INTERFACES).node(INTERFACE)
            .nodeWithKey(INTERFACE, NAME, name).node(OPER_STATUS).build(), ImmutableNodes.leafNode(OPER_STATUS, status));
        tx.commit().get();
    }

    static void adminStatus(String name, String status) throws Exception {
        var tx = server.dataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.builder().node(INTERFACES).node(INTERFACE)
            .nodeWithKey(INTERFACE, NAME, name).node(ADMIN_STATUS).build(), ImmutableNodes.leafNode(ADMIN_STATUS, status));
        tx.commit().get();
    }

    static List<String> await(List<NotificationMessage> inbox, int count, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (inbox.size() < count && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        List<String> out = new ArrayList<>();
        for (var m : List.copyOf(inbox)) {
            out.add(serialize(m.getDocument()));
        }
        capture(out);
        return out;
    }

    /** Every notification the server emitted, one per line, for the client-side probe (ClientSideProbe). */
    static synchronized void capture(List<String> notifications) throws Exception {
        var file = java.nio.file.Path.of(System.getProperty("yp.capture", "target/captured-notifications.txt"));
        var seen = java.nio.file.Files.exists(file) ? new java.util.HashSet<>(java.nio.file.Files.readAllLines(file))
            : new java.util.HashSet<String>();
        var add = new ArrayList<String>();
        for (var n : notifications) {
            String oneLine = n.replace("\n", "&#10;"); // the publisher's anydata workaround adds newlines
            if (seen.add(oneLine)) {
                add.add(oneLine);
            }
        }
        java.nio.file.Files.write(file, add, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
    }

    static void ensureInterface(NetconfOperationRouter router, String name) throws Exception {
        String xml = "<interfaces xmlns=\"" + IF_NS + "\"><interface><name>" + name + "</name>"
            + "<type xmlns:ianaift=\"urn:ietf:params:xml:ns:yang:iana-if-type\">ianaift:ethernetCsmacd</type>"
            + "<enabled>true</enabled></interface></interfaces>";
        String r = rpc(router, "<edit-config><target><candidate/></target><config>" + xml + "</config></edit-config>");
        assertTrue(r, r.contains("<ok"));
        r = rpc(router, "<commit/>");
        assertTrue(r, r.contains("<ok"));
    }

    // ---- tests -----------------------------------------------------------------------------------------------

    @Test
    public void advertisesTheSubscriptionCapabilitiesAndSchemas() {
        String caps = server.capabilities().toString() + server.monitoring().getCapabilities();
        for (String c : new String[] {"capability:notification:2.0", "capability:yang-push:1.0",
            "ietf-subscribed-notifications", "ietf-yang-push", "ietf-yang-patch"}) {
            assertTrue(c + " missing in " + caps, caps.contains(c));
        }
        System.out.println("CAPS-OK " + caps.length());
    }

    @Test
    public void onChangeOperStatusEndToEnd() throws Exception {
        var inbox = new CopyOnWriteArrayList<NotificationMessage>();
        currentInbox = inbox;
        var router = server.newLocalRouter();
        ensureInterface(router, "10.0.0.1|10.0.0.2");

        String reply = rpc(router, establishXPath("/if:interfaces/if:interface/if:oper-status", 0));
        long id = idOf(reply);
        System.out.println("ESTABLISH-REPLY " + reply);

        // 1. first state (leaf appears) -> create
        operStatus("10.0.0.1|10.0.0.2", "up");
        Thread.sleep(150);
        // 2. a real change -> replace
        operStatus("10.0.0.1|10.0.0.2", "down");
        Thread.sleep(150);
        // 3. same value rewritten -> nothing
        operStatus("10.0.0.1|10.0.0.2", "down");
        Thread.sleep(150);
        // 4. a sibling the filter does not select -> nothing
        adminStatus("10.0.0.1|10.0.0.2", "down");
        Thread.sleep(150);
        // 5. back up
        operStatus("10.0.0.1|10.0.0.2", "up");

        var got = await(inbox, 3, 3000);
        Thread.sleep(300);
        got = await(inbox, 3, 100);
        System.out.println("PUSH-COUNT " + got.size());
        got.forEach(g -> System.out.println("PUSH " + g));
        assertEquals("create + replace + replace, no no-op, no admin-status", 3, got.size());
        assertTrue(got.get(0).contains("<operation>create</operation>"));
        assertTrue(got.get(0), got.get(0).contains("<target>/ietf-interfaces:interfaces/interface=10.0.0.1%7C10.0.0.2/oper-status</target>"));
        assertTrue(got.get(1).contains("<operation>replace</operation>") && got.get(1).contains(">down<"));
        assertTrue(got.get(2).contains("<operation>replace</operation>") && got.get(2).contains(">up<"));
        assertTrue(got.get(0).contains("<id>" + id + "</id>"));

        // /subscriptions state is readable with <get>
        String state = rpc(router, "<get><filter type=\"subtree\"><subscriptions xmlns=\"" + YangPushPrototype.SN_NS
            + "\"/></filter></get>");
        System.out.println("STATE " + state);
        assertTrue(state, state.contains("<id>" + id + "</id>"));
        assertTrue(state, state.contains("datastore-xpath-filter"));

        // delete-subscription: no more pushes, state removed
        String del = rpc(router, "<delete-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><id>" + id
            + "</id></delete-subscription>");
        assertTrue(del, del.contains("<ok"));
        int before = inbox.size();
        operStatus("10.0.0.1|10.0.0.2", "down");
        Thread.sleep(200);
        assertEquals(before, inbox.size());
        String again = rpc(router, "<get><filter type=\"subtree\"><subscriptions xmlns=\"" + YangPushPrototype.SN_NS
            + "\"/></filter></get>");
        assertFalse(again, again.contains("<id>" + id + "</id>"));
        assertEquals(0, yp.activeSubscriptions());
    }

    @Test
    public void syncOnStartSendsOnePushUpdateWithTheCurrentSelection() throws Exception {
        var inbox = new CopyOnWriteArrayList<NotificationMessage>();
        currentInbox = inbox;
        var router = server.newLocalRouter();
        ensureInterface(router, "sync-a");
        ensureInterface(router, "sync-b");
        operStatus("sync-a", "up");
        operStatus("sync-b", "down");
        Thread.sleep(200);
        long id = idOf(rpc(router, establishXPath("/if:interfaces/if:interface/if:oper-status", 0, true)));
        var got = await(inbox, 1, 3000);
        Thread.sleep(200);
        got = await(inbox, 1, 100);
        got.forEach(g -> System.out.println("SYNC " + g));
        assertEquals(1, got.size());
        String u = got.get(0);
        assertTrue(u, u.contains("<push-update") && u.contains("<datastore-contents>"));
        assertTrue(u, u.contains("<name>sync-a</name><oper-status>up</oper-status>"));
        assertTrue(u, u.contains("<name>sync-b</name><oper-status>down</oper-status>"));
        // after the sync only changes follow
        operStatus("sync-a", "down");
        got = await(inbox, 2, 3000);
        got.forEach(g -> System.out.println("SYNC-THEN " + g));
        assertEquals(2, got.size());
        assertTrue(got.get(1).contains("<push-change-update") && got.get(1).contains("interface=sync-a/oper-status"));
        rpc(router, "<delete-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><id>" + id + "</id></delete-subscription>");
    }

    @Test
    public void subtreeFilterAndDampeningCoalesce() throws Exception {
        var inbox = new CopyOnWriteArrayList<NotificationMessage>();
        currentInbox = inbox;
        var router = server.newLocalRouter();
        ensureInterface(router, "dampened");
        long id = idOf(rpc(router, establishSubtree(30))); // 300 ms
        operStatus("dampened", "up");
        operStatus("dampened", "down");
        operStatus("dampened", "up");
        operStatus("dampened", "down");
        Thread.sleep(100);
        assertEquals("nothing before the dampening period elapsed", 0, inbox.size());
        var got = await(inbox, 1, 2000);
        Thread.sleep(500);
        got = await(inbox, 1, 100);
        got.forEach(g -> System.out.println("DAMPENED " + g));
        assertEquals(1, got.size());
        assertTrue(got.get(0).contains("<operation>create</operation>") && got.get(0).contains(">down<"));
        rpc(router, "<delete-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><id>" + id + "</id></delete-subscription>");
    }

    @Test
    public void ancestorCreateAndDeleteExpandToTheSelectedLeaf() throws Exception {
        var inbox = new CopyOnWriteArrayList<NotificationMessage>();
        currentInbox = inbox;
        var router = server.newLocalRouter();
        long id = idOf(rpc(router, establishXPath("/if:interfaces/if:interface/if:oper-status", 0)));
        // write a whole interface entry (as a mirror write of config would), then delete it
        var tx = server.dataBroker().newWriteOnlyTransaction();
        var entry = ImmutableNodes.newMapEntryBuilder().withNodeIdentifier(
                YangInstanceIdentifier.NodeIdentifierWithPredicates.of(INTERFACE, NAME, "whole"))
            .withChild(ImmutableNodes.leafNode(NAME, "whole"))
            .withChild(ImmutableNodes.leafNode(OPER_STATUS, "down")).build();
        tx.put(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.builder().node(INTERFACES).node(INTERFACE)
            .nodeWithKey(INTERFACE, NAME, "whole").build(), entry);
        tx.commit().get();
        Thread.sleep(200); // a create and a delete in one batch would net out to nothing, correctly
        var tx2 = server.dataBroker().newWriteOnlyTransaction();
        tx2.delete(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.builder().node(INTERFACES).node(INTERFACE)
            .nodeWithKey(INTERFACE, NAME, "whole").build());
        tx2.commit().get();
        var got = await(inbox, 2, 3000);
        got.forEach(g -> System.out.println("EXPANDED " + g));
        assertEquals(2, got.size());
        assertTrue(got.get(0).contains("<operation>create</operation>") && got.get(0).contains("interface=whole/oper-status"));
        assertTrue(got.get(1).contains("<operation>delete</operation>") && got.get(1).contains("interface=whole/oper-status"));
        assertFalse(got.get(1).contains("<value>"));
        rpc(router, "<delete-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><id>" + id + "</id></delete-subscription>");
    }

    @Test
    public void killAndSessionCloseAndRejections() throws Exception {
        var inboxA = new CopyOnWriteArrayList<NotificationMessage>();
        currentInbox = inboxA;
        var a = server.newLocalRouter();
        long idA = idOf(rpc(a, establishXPath("/if:interfaces/if:interface/if:oper-status", 0)));
        var b = server.newLocalRouter();

        // another session cannot delete it, but kill-subscription (operator) can, and the owner is told
        String denied = rpc2(b, "<delete-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><id>" + idA
            + "</id></delete-subscription>");
        System.out.println("DELETE-OTHER-SESSION " + denied);
        assertTrue(denied, denied.contains("rpc-error"));
        String kill = rpc(b, "<kill-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><id>" + idA
            + "</id></kill-subscription>");
        assertTrue(kill, kill.contains("<ok"));
        var got = await(inboxA, 1, 2000);
        got.forEach(g -> System.out.println("TERMINATED " + g));
        assertTrue(got.get(0).contains("subscription-terminated") && got.get(0).contains("no-such-subscription"));

        // session close ends its subscriptions
        var c = server.newLocalRouter();
        idOf(rpc(c, establishXPath("/if:interfaces/if:interface/if:oper-status", 0)));
        assertEquals(1, yp.activeSubscriptions());
        ((org.opendaylight.netconf.server.osgi.NetconfOperationRouterImpl) c).close();
        assertEquals(0, yp.activeSubscriptions());

        // rejected requests (error tags are what a client would see)
        String noFilter = rpc2(b, "<establish-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><datastore xmlns=\""
            + YangPushPrototype.YP_NS + "\" xmlns:ds=\"" + YangPushPrototype.DS_NS + "\">ds:operational</datastore>"
            + "<on-change xmlns=\"" + YangPushPrototype.YP_NS + "\"/></establish-subscription>");
        System.out.println("REJECT-NO-FILTER " + noFilter);
        assertTrue(noFilter, noFilter.contains("on-change-unsupported"));
        String unsupportedNode = rpc2(b, establishXPath("/if:interfaces/if:interface/if:admin-status", 0));
        System.out.println("REJECT-UNSUPPORTED-NODE " + unsupportedNode);
        assertTrue(unsupportedNode, unsupportedNode.contains("on-change-unsupported"));
        String periodic = rpc2(b, "<establish-subscription xmlns=\"" + YangPushPrototype.SN_NS + "\"><datastore xmlns=\""
            + YangPushPrototype.YP_NS + "\" xmlns:ds=\"" + YangPushPrototype.DS_NS + "\">ds:operational</datastore>"
            + "<periodic xmlns=\"" + YangPushPrototype.YP_NS + "\"><period>500</period></periodic>"
            + "</establish-subscription>");
        assertTrue(periodic, periodic.contains("period-unsupported"));
        assertEquals(0, yp.activeSubscriptions());
    }

    /** Like {@link #rpc} but returns the rpc-error text instead of throwing. */
    static String rpc2(NetconfOperationRouter router, String body) throws Exception {
        try {
            return rpc(router, body);
        } catch (org.opendaylight.netconf.api.DocumentedException e) {
            return "rpc-error " + e.getErrorTag() + " " + e.getMessage();
        }
    }
}
