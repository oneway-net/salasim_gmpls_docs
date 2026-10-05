import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import net.salasim.netconf.mgmt.YangResources;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.codec.xml.XmlParserStream;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizationResultHolder;
import org.opendaylight.yangtools.yang.model.util.SchemaInferenceStack;
import org.xml.sax.InputSource;
public class AnyProbe {
  static final String SN="urn:ietf:params:xml:ns:yang:ietf-subscribed-notifications", YP="urn:ietf:params:xml:ns:yang:ietf-yang-push", IF="urn:ietf:params:xml:ns:yang:ietf-interfaces";
  public static void main(String[] a) throws Exception {
    var ctx = YangResources.load().context();
    String ds = "<datastore xmlns=\""+YP+"\" xmlns:ds=\"urn:ietf:params:xml:ns:yang:ietf-datastores\">ds:operational</datastore>";
    String oc = "<on-change xmlns=\""+YP+"\"><dampening-period>0</dampening-period></on-change>";
    String[][] cases = {
      {"anydata-last", ds + oc + "<datastore-subtree-filter xmlns=\""+YP+"\"><interfaces xmlns=\""+IF+"\"><interface><oper-status/></interface></interfaces></datastore-subtree-filter>"},
      {"anydata-middle", ds + "<datastore-subtree-filter xmlns=\""+YP+"\"><interfaces xmlns=\""+IF+"\"><interface><oper-status/></interface></interfaces></datastore-subtree-filter>" + oc},
      {"anydata-only", ds + "<datastore-subtree-filter xmlns=\""+YP+"\"><interfaces xmlns=\""+IF+"\"/></datastore-subtree-filter>"},
      {"anydata-flat-middle", ds + "<datastore-subtree-filter xmlns=\""+YP+"\"><interfaces xmlns=\""+IF+"\"/></datastore-subtree-filter>" + oc},
    };
    for (var c : cases) {
      try {
        var rpc = QName.create(SN, "2019-09-09", "establish-subscription");
        var stack = SchemaInferenceStack.of(ctx);
        stack.enterSchemaTree(rpc); stack.enterSchemaTree(QName.create(rpc, "input"));
        var result = new NormalizationResultHolder();
        var writer = ImmutableNormalizedNodeStreamWriter.from(result);
        var dbf = DocumentBuilderFactory.newInstance(); dbf.setNamespaceAware(true);
        var doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader("<input xmlns=\""+SN+"\">"+c[1]+"</input>")));
        XmlParserStream.create(writer, stack.toInference()).traverse(new DOMSource(doc.getDocumentElement()));
        System.out.println("ANY " + c[0] + " OK " + result.getResult().data().toString().length());
      } catch (Throwable t) { System.out.println("ANY " + c[0] + " FAIL " + t.getMessage().replaceAll("\\s+"," ").substring(0, Math.min(200, t.getMessage().length()))); }
    }
  }
}
