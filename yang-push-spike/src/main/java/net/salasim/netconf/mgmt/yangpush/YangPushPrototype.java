package net.salasim.netconf.mgmt.yangpush;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMDataTreeChangeListener;
import org.opendaylight.mdsal.dom.api.DOMDataTreeIdentifier;
import org.opendaylight.netconf.api.DocumentedException;
import org.opendaylight.netconf.api.NetconfSession;
import org.opendaylight.netconf.api.messages.NotificationMessage;
import org.opendaylight.netconf.api.xml.XmlElement;
import org.opendaylight.netconf.server.api.monitoring.BasicCapability;
import org.opendaylight.netconf.server.api.monitoring.Capability;
import org.opendaylight.netconf.server.api.monitoring.CapabilityListener;
import org.opendaylight.netconf.server.api.operations.AbstractSingletonNetconfOperation;
import org.opendaylight.netconf.server.api.operations.NetconfOperation;
import org.opendaylight.netconf.server.api.operations.NetconfOperationService;
import org.opendaylight.netconf.server.api.operations.NetconfOperationServiceFactory;
import org.opendaylight.netconf.server.api.operations.SessionAwareNetconfOperation;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.netconf.base._1._0.rev110601.SessionIdType;
import org.opendaylight.yangtools.concepts.Registration;
import org.opendaylight.yangtools.yang.common.ErrorSeverity;
import org.opendaylight.yangtools.yang.common.ErrorTag;
import org.opendaylight.yangtools.yang.common.ErrorType;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.QNameModule;
import org.opendaylight.yangtools.yang.common.XMLNamespace;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifierWithPredicates;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.LeafNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.codec.xml.XmlParserStream;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizationResultHolder;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeCandidate;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeCandidateNode;
import org.opendaylight.yangtools.yang.data.tree.api.ModificationType;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * SPIKE (Phase 0.3), not production code. A session-bound RFC 8639 / RFC 8641 publisher for NETCONF, built on the ODL
 * netconf-server operation SPI ({@link SessionAwareNetconfOperation}, the same hook ODL's own RFC 5277
 * {@code create-subscription} uses) and the MD-SAL DOM data tree change listener of the OPERATIONAL datastore.
 *
 * <p>Implemented subset: establish-subscription (datastore target {@code ds:operational}, xpath or subtree filter
 * restricted to one absolute child-axis path, {@code on-change} with {@code dampening-period}), delete-subscription,
 * kill-subscription, push-change-update with an RFC 8072 yang-patch, subscription-terminated, the
 * {@code /subscriptions} state tree, termination with the session. Not implemented: modify-subscription, periodic,
 * stream subscriptions, replay, filter references, predicates in filters, non-leaf edit values, sync-on-start.
 */
public final class YangPushPrototype implements NetconfOperationServiceFactory, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(YangPushPrototype.class);

    public static final String SN_NS = "urn:ietf:params:xml:ns:yang:ietf-subscribed-notifications";
    public static final String YP_NS = "urn:ietf:params:xml:ns:yang:ietf-yang-push";
    public static final String DS_NS = "urn:ietf:params:xml:ns:yang:ietf-datastores";

    /** Where notifications for one NETCONF session go (production: {@code session::sendMessage}). */
    @FunctionalInterface
    public interface Sink {
        void send(NotificationMessage message);
    }

    private final DOMDataBroker broker;
    private final Supplier<EffectiveModelContext> schema;
    private final BiFunction<SessionIdType, NetconfSession, Sink> sinks;
    private final ScheduledExecutorService timer;
    private final ConcurrentMap<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    /** The data this publisher is willing to push on change (RFC 8641 'on-change-unsupported' otherwise). */
    private final Set<List<QName>> onChangeSupported;

    public YangPushPrototype(DOMDataBroker broker, Supplier<EffectiveModelContext> schema,
            BiFunction<SessionIdType, NetconfSession, Sink> sinks, ScheduledExecutorService timer,
            Set<List<QName>> onChangeSupported) {
        this.broker = broker;
        this.schema = schema;
        this.sinks = sinks;
        this.timer = timer;
        this.onChangeSupported = onChangeSupported;
    }

    // ---- NetconfOperationServiceFactory --------------------------------------------------------------------------

    @Override
    public Set<Capability> getCapabilities() {
        // RFC 8640 section 2/3 (from memory, not machine-verified offline): these two URIs
        return Set.of(new BasicCapability("urn:ietf:params:netconf:capability:notification:2.0"),
            new BasicCapability("urn:ietf:params:netconf:capability:yang-push:1.0"));
    }

    @Override
    public Registration registerCapabilityListener(CapabilityListener listener) {
        return () -> { };
    }

    @Override
    public NetconfOperationService createService(SessionIdType sessionId) {
        var session = new SessionState(sessionId);
        var ops = Set.<NetconfOperation>of(new Op(session, "establish-subscription"),
            new Op(session, "delete-subscription"), new Op(session, "kill-subscription"));
        return new NetconfOperationService() {
            @Override
            public Set<NetconfOperation> getNetconfOperations() {
                return ops;
            }

            @Override
            public void close() {
                session.closeAll();
            }
        };
    }

    @Override
    public void close() {
        subscriptions.values().forEach(s -> s.stop(false, null));
    }

    public int activeSubscriptions() {
        return subscriptions.size();
    }

    // ---- per session ---------------------------------------------------------------------------------------------

    private final class SessionState {
        final SessionIdType id;
        volatile NetconfSession session;
        final List<Subscription> owned = new ArrayList<>();

        SessionState(SessionIdType id) {
            this.id = id;
        }

        void closeAll() {
            for (var s : List.copyOf(owned)) {
                s.stop(false, null); // the transport is gone: RFC 8639 dynamic subscriptions die with the session
            }
            owned.clear();
        }
    }

    private final class Op extends AbstractSingletonNetconfOperation implements SessionAwareNetconfOperation {
        private final SessionState state;
        private final String name;

        Op(SessionState state, String name) {
            super(state.id);
            this.state = state;
            this.name = name;
        }

        @Override
        public void setSession(NetconfSession session) {
            state.session = session;
        }

        @Override
        protected String getOperationName() {
            return name;
        }

        @Override
        protected String getOperationNamespace() {
            return SN_NS;
        }

        @Override
        protected Element handleWithNoSubsequentOperations(Document doc, XmlElement operation)
                throws DocumentedException {
            Element in = operation.getDomElement();
            return switch (name) {
                case "establish-subscription" -> establish(doc, in, state);
                case "delete-subscription" -> {
                    var s = lookup(in);
                    if (s.owner != state) {
                        throw error(ErrorTag.INVALID_VALUE, "no-such-subscription");
                    }
                    s.stop(true, null);
                    yield doc.createElementNS("urn:ietf:params:xml:ns:netconf:base:1.0", "ok");
                }
                default -> {
                    var s = lookup(in);
                    s.stop(true, "no-such-subscription"); // RFC 8639 has no 'killed' reason; the YANG says this one
                    yield doc.createElementNS("urn:ietf:params:xml:ns:netconf:base:1.0", "ok");
                }
            };
        }

        private Subscription lookup(Element in) throws DocumentedException {
            String text = childText(in, "id");
            try {
                var s = subscriptions.get(Long.parseLong(text.trim()));
                if (s != null) {
                    return s;
                }
            } catch (RuntimeException e) {
                // fall through
            }
            throw error(ErrorTag.INVALID_VALUE, "no-such-subscription");
        }
    }

    // ---- establish -----------------------------------------------------------------------------------------------

    private Element establish(Document out, Element in, SessionState owner) throws DocumentedException {
        Element encoding = child(in, SN_NS, "encoding");
        if (encoding != null && !"encode-xml".equals(local(encoding.getTextContent().trim()))) {
            throw error(ErrorTag.OPERATION_NOT_SUPPORTED, "encoding-unsupported");
        }
        if (child(in, SN_NS, "stream") != null) {
            throw error(ErrorTag.OPERATION_NOT_SUPPORTED, "stream subscriptions are not implemented");
        }
        if (child(in, SN_NS, "replay-start-time") != null) {
            throw error(ErrorTag.OPERATION_NOT_SUPPORTED, "replay-unsupported");
        }
        Element ds = child(in, YP_NS, "datastore");
        if (ds == null) {
            throw error(ErrorTag.MISSING_ELEMENT, "datastore");
        }
        String dsText = ds.getTextContent().trim();
        String ns = prefixNamespace(ds, dsText);
        if (!DS_NS.equals(ns) || !"operational".equals(local(dsText))) {
            throw error(ErrorTag.OPERATION_FAILED, "datastore-not-subscribable");
        }
        Element onChange = child(in, YP_NS, "on-change");
        if (onChange == null) {
            throw error(ErrorTag.OPERATION_FAILED, "period-unsupported (only on-change is implemented)");
        }
        String damp = childText(onChange, "dampening-period", "0");
        long dampCs = Long.parseLong(damp.trim());
        // RFC 8641: sync-on-start defaults to TRUE: a push-update with the full selection starts every subscription
        boolean syncOnStart = !"false".equals(childText(onChange, "sync-on-start", "true").trim());

        List<QName> steps;
        Element xpath = child(in, YP_NS, "datastore-xpath-filter");
        Element subtree = child(in, YP_NS, "datastore-subtree-filter");
        if (xpath != null) {
            steps = parseXPath(xpath);
        } else if (subtree != null) {
            steps = parseSubtree(subtree);
        } else {
            throw error(ErrorTag.OPERATION_FAILED, "on-change-unsupported (an unfiltered datastore subscription)");
        }
        if (!onChangeSupported.contains(steps)) {
            throw error(ErrorTag.OPERATION_FAILED, "on-change-unsupported: " + steps);
        }

        long id = ids.getAndIncrement();
        var sink = sinks.apply(owner.id, owner.session);
        var sub = new Subscription(id, owner, sink, steps, dampCs * 10L, xml(xpath, subtree), syncOnStart);
        subscriptions.put(id, sub);
        owner.owned.add(sub);
        sub.start();
        Element idEl = out.createElementNS(SN_NS, "id");
        idEl.setTextContent(Long.toString(id));
        return idEl;
    }

    private static String xml(Element xpath, Element subtree) {
        // kept for the operational /subscriptions entry
        try {
            var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            var w = new java.io.StringWriter();
            t.transform(new javax.xml.transform.dom.DOMSource(xpath != null ? xpath : subtree),
                new javax.xml.transform.stream.StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Absolute child-axis path without predicates: {@code /if:interfaces/if:interface/if:oper-status}. */
    private List<QName> parseXPath(Element xpath) throws DocumentedException {
        String text = xpath.getTextContent().trim();
        if (!text.startsWith("/") || text.contains("[") || text.contains("//") || text.contains("*")) {
            throw error(ErrorTag.OPERATION_FAILED, "filter-unsupported (only absolute child paths): " + text);
        }
        List<QName> steps = new ArrayList<>();
        QNameModule current = null;
        for (String step : text.substring(1).split("/")) {
            int colon = step.indexOf(':');
            if (colon > 0) {
                String uri = xpath.lookupNamespaceURI(step.substring(0, colon));
                if (uri == null) {
                    throw error(ErrorTag.OPERATION_FAILED, "unbound prefix in " + step);
                }
                current = module(uri);
                steps.add(QName.create(current, step.substring(colon + 1)));
            } else {
                if (current == null) {
                    throw error(ErrorTag.OPERATION_FAILED, "first step needs a prefix: " + step);
                }
                steps.add(QName.create(current, step));
            }
        }
        return steps;
    }

    /** A subtree filter that is one chain of nested elements, e.g. interfaces/interface/oper-status. */
    private List<QName> parseSubtree(Element filter) throws DocumentedException {
        List<QName> steps = new ArrayList<>();
        Element e = firstElement(filter);
        while (e != null) {
            steps.add(QName.create(module(e.getNamespaceURI()), e.getLocalName()));
            Element next = firstElement(e);
            if (next != null && countElements(e) != 1) {
                throw error(ErrorTag.OPERATION_FAILED, "filter-unsupported (one chain of elements only)");
            }
            e = next;
        }
        if (steps.isEmpty()) {
            throw error(ErrorTag.OPERATION_FAILED, "empty subtree filter");
        }
        return steps;
    }

    private QNameModule module(String namespace) throws DocumentedException {
        return schema.get().findModules(XMLNamespace.of(namespace)).stream().findFirst()
            .orElseThrow(() -> error(ErrorTag.OPERATION_FAILED, "unknown namespace " + namespace)).getQNameModule();
    }

    // ---- one subscription ----------------------------------------------------------------------------------------

    private final class Subscription implements DOMDataTreeChangeListener {
        final long id;
        final SessionState owner;
        final Sink sink;
        final List<QName> steps;
        final long dampeningMs;
        final String filterXml;
        final boolean syncOnStart;
        private Registration registration;
        private final Map<String, Edit> pending = new LinkedHashMap<>();
        private ScheduledFuture<?> flush;
        private long patchId = 1;
        private boolean stopped;

        Subscription(long id, SessionState owner, Sink sink, List<QName> steps, long dampeningMs, String filterXml,
                boolean syncOnStart) {
            this.syncOnStart = syncOnStart;
            this.id = id;
            this.owner = owner;
            this.sink = sink;
            this.steps = steps;
            this.dampeningMs = dampeningMs;
            this.filterXml = filterXml;
        }

        void start() {
            var ext = broker.extension(DOMDataBroker.DataTreeChangeExtension.class);
            registration = ext.registerTreeChangeListener(DOMDataTreeIdentifier.of(LogicalDatastoreType.OPERATIONAL,
                YangInstanceIdentifier.of(steps.get(0))), this);
            publishState();
        }

        /** mdsal delivers the current content of the subtree first (or this call when it is empty). */
        private boolean awaitingInitial = true;

        @Override
        public synchronized void onInitialData() {
            awaitingInitial = false; // empty subtree: nothing to skip
            if (syncOnStart) {
                sendPushUpdate(Map.of());
            }
        }

        @Override
        public synchronized void onDataTreeChanged(List<DataTreeCandidate> changes) {
            if (stopped) {
                return;
            }
            if (awaitingInitial) {
                // The existing state is not a change. With sync-on-start (the default) it is sent as one push-update.
                awaitingInitial = false;
                if (syncOnStart) {
                    var snapshot = new LinkedHashMap<List<YangInstanceIdentifier.PathArgument>, NormalizedNode>();
                    for (var c : changes) {
                        var after = c.getRootNode().dataAfter();
                        if (after != null) {
                            expand(after, new ArrayList<>(c.getRootPath().getPathArguments()), snapshot);
                        }
                    }
                    sendPushUpdate(snapshot);
                }
                return;
            }
            var edits = new ArrayList<Edit>();
            for (var c : changes) {
                collect(c.getRootNode(), new ArrayList<>(c.getRootPath().getPathArguments()), edits);
            }
            // net effect per target: first 'before' against last 'after' (a batch or a dampening period may hold
            // several changes of one node; up->down->up is no change at all)
            for (var e : edits) {
                var prev = pending.get(e.target);
                pending.put(e.target, prev == null ? e : new Edit(e.target, prev.before, e.after, e.name));
            }
            if (dampeningMs <= 0) {
                flushNow();
            } else if (flush == null) {
                flush = timer.schedule(this::flushPending, dampeningMs, TimeUnit.MILLISECONDS);
            }
        }

        private synchronized void flushPending() {
            flush = null;
            flushNow();
        }

        private void flushNow() {
            if (stopped) {
                return;
            }
            var net = new ArrayList<Edit>();
            for (var e : pending.values()) {
                if (!java.util.Objects.equals(e.before, e.after)) {
                    net.add(e);
                }
            }
            pending.clear();
            if (!net.isEmpty()) {
                send(net);
            }
        }

        /** The RFC 8641 'push-update': the selection as a {@code <get>} would return it, in an anydata. */
        private void sendPushUpdate(Map<List<YangInstanceIdentifier.PathArgument>, NormalizedNode> snapshot) {
            try {
                var doc = newDoc();
                var root = doc.createElementNS(YP_NS, "push-update");
                doc.appendChild(root);
                text(doc, root, YP_NS, "id", Long.toString(id));
                var contents = doc.createElementNS(YP_NS, "datastore-contents");
                root.appendChild(contents);
                var built = new java.util.HashMap<String, Element>();
                for (var entry : snapshot.entrySet()) {
                    Node parent = contents;
                    var path = entry.getKey();
                    var key = new StringBuilder();
                    for (int i = 0; i < path.size(); i++) {
                        var a = path.get(i);
                        key.append('/').append(a);
                        boolean listWrapper = a instanceof NodeIdentifier && i + 1 < path.size()
                            && path.get(i + 1) instanceof NodeIdentifierWithPredicates;
                        if (listWrapper) {
                            continue; // the entry below carries the list's element name
                        }
                        Element el = built.get(key.toString());
                        if (el == null) {
                            el = doc.createElementNS(a.getNodeType().getNamespace().toString(),
                                a.getNodeType().getLocalName());
                            parent.appendChild(el);
                            built.put(key.toString(), el);
                            if (a instanceof NodeIdentifierWithPredicates keyed) {
                                for (var k : keyed.entrySet()) {
                                    var kel = doc.createElementNS(k.getKey().getNamespace().toString(),
                                        k.getKey().getLocalName());
                                    kel.setTextContent(String.valueOf(k.getValue()));
                                    el.appendChild(kel);
                                }
                            } else if (i == path.size() - 1 && entry.getValue() instanceof LeafNode<?> leaf) {
                                el.setTextContent(String.valueOf(leaf.body()));
                            }
                        }
                        parent = el;
                    }
                }
                // anydata is last in push-update (the optional leaf after it is never sent) + the whitespace workaround
                root.appendChild(doc.createTextNode("\n"));
                sink.send(NotificationMessage.ofNotificationContent(doc));
            } catch (Exception ex) {
                LOG.error("push-update for subscription {} failed", id, ex);
            }
        }

        private void send(List<Edit> edits) {
            try {
                var doc = newDoc();
                var root = doc.createElementNS(YP_NS, "push-change-update");
                doc.appendChild(root);
                text(doc, root, YP_NS, "id", Long.toString(id));
                var changes = doc.createElementNS(YP_NS, "datastore-changes");
                root.appendChild(changes);
                // 'uses ypatch:yang-patch' instantiates the grouping in THIS module: yang-patch and all of its
                // children are in the ietf-yang-push namespace, not ietf-yang-patch (found by the client-side parse)
                var patch = doc.createElementNS(YP_NS, "yang-patch");
                changes.appendChild(patch);
                text(doc, patch, YP_NS, "patch-id", Long.toString(patchId++));
                int n = 1;
                for (var e : edits) {
                    var edit = doc.createElementNS(YP_NS, "edit");
                    patch.appendChild(edit);
                    text(doc, edit, YP_NS, "edit-id", Integer.toString(n++));
                    text(doc, edit, YP_NS, "operation", e.op());
                    text(doc, edit, YP_NS, "target", e.target);
                    if (e.after != null) {
                        // anydata LAST: yangtools 15.0.2 XmlParserStream mis-parses an anydata followed by a sibling
                        var value = doc.createElementNS(YP_NS, "value");
                        edit.appendChild(value);
                        var leaf = doc.createElementNS(e.name.getNamespace().toString(), e.name.getLocalName());
                        leaf.setTextContent(String.valueOf(e.after));
                        value.appendChild(leaf);
                        // WORKAROUND (yangtools 15.0.2 XmlParserStream, also what the controller's NETCONF client
                        // uses): an anydata element directly followed by a tag derails the parser; one whitespace text
                        // node after it fixes it (ClientSideProbe/Matrix experiments). Remove when upstream is fixed.
                        edit.appendChild(doc.createTextNode("\n"));
                    }
                }
                sink.send(NotificationMessage.ofNotificationContent(doc));
            } catch (Exception ex) {
                LOG.error("push-change-update for subscription {} failed", id, ex);
            }
        }

        /** Publisher- or operator-initiated end; {@code reason} null means subscriber-initiated or transport gone. */
        void stop(boolean notifyOwner, String reason) {
            synchronized (this) {
                if (stopped) {
                    return;
                }
                stopped = true;
                if (flush != null) {
                    flush.cancel(false);
                }
            }
            if (registration != null) {
                registration.close();
            }
            subscriptions.remove(id);
            owner.owned.remove(this);
            if (reason != null) {
                try {
                    var doc = newDoc();
                    var root = doc.createElementNS(SN_NS, "subscription-terminated");
                    doc.appendChild(root);
                    text(doc, root, SN_NS, "id", Long.toString(id));
                    var r = text(doc, root, SN_NS, "reason", reason);
                    r.setTextContent(reason);
                    sink.send(NotificationMessage.ofNotificationContent(doc));
                } catch (Exception ex) {
                    LOG.warn("subscription-terminated for {} not sent", id, ex);
                }
            }
            var tx = broker.newWriteOnlyTransaction();
            tx.delete(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.builder()
                .node(qn(SN_NS, "subscriptions")).node(qn(SN_NS, "subscription"))
                .nodeWithKey(qn(SN_NS, "subscription"), qn(SN_NS, "id"),
                    org.opendaylight.yangtools.yang.common.Uint32.valueOf(id)).build());
            tx.commit().addCallback(logOnly("delete /subscriptions entry"), MoreExecutors.directExecutor());
        }

        private QName qn(String ns, String local) {
            return QName.create(module(ns), local);
        }

        private QNameModule module(String namespace) {
            return schema.get().findModules(XMLNamespace.of(namespace)).iterator().next().getQNameModule();
        }

        /** The {@code /subscriptions/subscription} entry (RFC 8639 state), parsed from XML with the real schema. */
        private void publishState() {
            try {
                String xml = "<subscriptions xmlns=\"" + SN_NS + "\"><subscription><id>" + id + "</id>"
                    + "<datastore xmlns=\"" + YP_NS + "\" xmlns:ds=\"" + DS_NS + "\">ds:operational</datastore>"
                    + (filterXml.contains("datastore-subtree-filter") ? "" : filterXml)
                    + "<encoding>encode-xml</encoding>"
                    + "<on-change xmlns=\"" + YP_NS + "\"><dampening-period>" + dampeningMs / 10 + "</dampening-period>"
                    + "</on-change>"
                    + "<receivers><receiver><name>session-" + owner.id.getValue() + "</name><state>active</state>"
                    + "</receiver></receivers>"
                    + (filterXml.contains("datastore-subtree-filter") ? filterXml + "\n" : "") // anydata + whitespace (parser quirk)
                    + "</subscription></subscriptions>";
                var result = new NormalizationResultHolder();
                var writer = ImmutableNormalizedNodeStreamWriter.from(result);
                var reader = XMLInputFactory.newDefaultFactory().createXMLStreamReader(new StringReader(xml));
                // the root element is the container itself; its children (the list) are what gets parsed
                var inference = org.opendaylight.yangtools.yang.model.util.SchemaInferenceStack.of(schema.get(),
                    org.opendaylight.yangtools.yang.model.api.stmt.SchemaNodeIdentifier.Absolute.of(
                        qn(SN_NS, "subscriptions"))).toInference();
                XmlParserStream.create(writer, inference).parse(reader);
                NormalizedNode data = result.getResult().data();
                var tx = broker.newWriteOnlyTransaction();
                tx.merge(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.of(qn(SN_NS, "subscriptions")), data);
                tx.commit().addCallback(logOnly("write /subscriptions entry"), MoreExecutors.directExecutor());
            } catch (Exception e) {
                LOG.error("cannot publish /subscriptions state for {}", id, e);
            }
        }

        private void collect(DataTreeCandidateNode node, List<YangInstanceIdentifier.PathArgument> path,
                List<Edit> edits) {
            int depth = schemaDepth(path);
            if (depth > steps.size() || depth == 0 && !path.isEmpty()) {
                return;
            }
            var arg = path.isEmpty() ? null : path.get(path.size() - 1);
            if (arg != null && !arg.getNodeType().equals(steps.get(depth - 1))) {
                return;
            }
            ModificationType type = node.modificationType();
            if (depth == steps.size()) {
                switch (type) {
                    case WRITE, APPEARED -> {
                        var before = node.dataBefore();
                        var after = node.dataAfter();
                        edits.add(edit(path, before, after));
                    }
                    case DELETE, DISAPPEARED -> edits.add(edit(path, node.dataBefore(), null));
                    case SUBTREE_MODIFIED -> edits.add(edit(path, node.dataBefore(), node.dataAfter()));
                    default -> { }
                }
                return;
            }
            switch (type) {
                case SUBTREE_MODIFIED -> {
                    for (var child : node.childNodes()) {
                        var childPath = new ArrayList<>(path);
                        childPath.add(child.name());
                        collect(child, childPath, edits);
                    }
                }
                case UNMODIFIED -> { }
                default -> {
                    // a whole ancestor was written or removed: diff the matching descendants before/after
                    var before = new LinkedHashMap<List<YangInstanceIdentifier.PathArgument>, NormalizedNode>();
                    var after = new LinkedHashMap<List<YangInstanceIdentifier.PathArgument>, NormalizedNode>();
                    if (node.dataBefore() != null) {
                        expand(node.dataBefore(), path, before);
                    }
                    if (node.dataAfter() != null) {
                        expand(node.dataAfter(), path, after);
                    }
                    after.forEach((p, v) -> edits.add(edit(p, before.get(p), v)));
                    before.forEach((p, v) -> {
                        if (!after.containsKey(p)) {
                            edits.add(edit(p, v, null));
                        }
                    });
                }
            }
        }

        /** Matching nodes at filter depth below {@code data} (which sits at {@code path}). */
        private void expand(NormalizedNode data, List<YangInstanceIdentifier.PathArgument> path,
                Map<List<YangInstanceIdentifier.PathArgument>, NormalizedNode> out) {
            int depth = schemaDepth(path);
            if (data instanceof MapNode map) {
                for (var entry : map.body()) {
                    var p = new ArrayList<>(path);
                    p.add(entry.name());
                    expand(entry, p, out);
                }
                return;
            }
            if (!data.name().getNodeType().equals(steps.get(Math.max(depth, 1) - 1)) && depth > 0) {
                return;
            }
            if (depth == steps.size()) {
                out.put(path, data);
                return;
            }
            if (data instanceof DataContainerNode container) {
                var wanted = steps.get(depth);
                for (var child : container.body()) {
                    if (child.name().getNodeType().equals(wanted)) {
                        var p = new ArrayList<>(path);
                        if (!(child instanceof MapNode)) {
                            p.add(child.name());
                        } else {
                            p.add(child.name());
                        }
                        expand(child, p, out);
                    }
                }
            }
        }

        private Edit edit(List<YangInstanceIdentifier.PathArgument> path, NormalizedNode before,
                NormalizedNode after) {
            QName name = steps.get(steps.size() - 1);
            return new Edit(resourceId(path), body(before), body(after), name);
        }

        private static Object body(NormalizedNode n) {
            return n instanceof LeafNode<?> leaf ? leaf.body() : null;
        }
    }

    /** One observed change of a selected node; {@code before}/{@code after} are leaf values (null = absent). */
    private record Edit(String target, Object before, Object after, QName name) {
        String op() {
            return before == null ? "create" : after == null ? "delete" : "replace";
        }
    }

    /** Number of schema-tree steps in {@code path} (list entries do not add a step). */
    private static int schemaDepth(List<YangInstanceIdentifier.PathArgument> path) {
        int n = 0;
        for (var a : path) {
            if (!(a instanceof NodeIdentifierWithPredicates)) {
                n++;
            }
        }
        return n;
    }

    /** RFC 8040 section 3.5.3 data resource identifier (the RFC 8072 {@code target} of an edit). */
    private String resourceId(List<YangInstanceIdentifier.PathArgument> path) {
        var ctx = schema.get();
        var sb = new StringBuilder();
        QNameModule parent = null;
        for (int i = 0; i < path.size(); i++) {
            var a = path.get(i);
            if (a instanceof NodeIdentifierWithPredicates keyed) {
                sb.append('=');
                boolean first = true;
                for (var v : keyed.values()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(java.net.URLEncoder.encode(String.valueOf(v), java.nio.charset.StandardCharsets.UTF_8));
                }
                continue;
            }
            if (!(a instanceof NodeIdentifier)) {
                continue;
            }
            // a list shows up as the MapNode's NodeIdentifier followed by the entry's keyed identifier
            if (i > 0 && path.get(i - 1) instanceof NodeIdentifier prev && prev.getNodeType().equals(a.getNodeType())) {
                continue;
            }
            var q = a.getNodeType();
            sb.append('/');
            if (!q.getModule().equals(parent)) {
                sb.append(ctx.findModule(q.getModule()).orElseThrow().getName()).append(':');
            }
            sb.append(q.getLocalName());
            parent = q.getModule();
        }
        return sb.toString();
    }

    // ---- small helpers ---------------------------------------------------------------------------------------------

    private static FutureCallback<CommitInfo> logOnly(String what) {
        return new FutureCallback<>() {
            @Override
            public void onSuccess(CommitInfo result) {
                // nothing
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.error("{} failed", what, t);
            }
        };
    }

    private static DocumentedException error(ErrorTag tag, String message) {
        return new DocumentedException(message, ErrorType.APPLICATION, tag, ErrorSeverity.ERROR);
    }

    private static Document newDoc() throws Exception {
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().newDocument();
    }

    private static Element text(Document doc, Element parent, String ns, String name, String value) {
        var e = doc.createElementNS(ns, name);
        e.setTextContent(value);
        parent.appendChild(e);
        return e;
    }

    private static Element child(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) {
                return e;
            }
        }
        return null;
    }

    /** Leaves of RPC input share the RPC's namespace but may arrive in a nested container's namespace. */
    private static String childText(Element parent, String local) throws DocumentedException {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) {
                return e.getTextContent();
            }
        }
        throw error(ErrorTag.MISSING_ELEMENT, local);
    }

    private static String childText(Element parent, String local, String dflt) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) {
                return e.getTextContent();
            }
        }
        return dflt;
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static int countElements(Element parent) {
        int c = 0;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                c++;
            }
        }
        return c;
    }

    private static String local(String qname) {
        int i = qname.indexOf(':');
        return i < 0 ? qname : qname.substring(i + 1);
    }

    private static String prefixNamespace(Element ctx, String qname) {
        int i = qname.indexOf(':');
        return ctx.lookupNamespaceURI(i < 0 ? null : qname.substring(0, i));
    }
}
