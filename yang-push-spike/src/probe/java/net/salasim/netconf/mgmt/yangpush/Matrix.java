import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import net.salasim.netconf.mgmt.YangResources;
import org.opendaylight.netconf.api.messages.NetconfMessage;
import org.opendaylight.netconf.client.mdsal.api.NetconfSessionPreferences;
import org.opendaylight.netconf.client.mdsal.impl.DefaultBaseNetconfSchemaProvider;
import org.opendaylight.netconf.client.mdsal.impl.NetconfMessageTransformer;
import org.opendaylight.yangtools.databind.DatabindContext;
import org.xml.sax.InputSource;
public class Matrix {
  static final String YP="urn:ietf:params:xml:ns:yang:ietf-yang-push", IF="urn:ietf:params:xml:ns:yang:ietf-interfaces";
  public static void main(String[] a) throws Exception {
    var ctx = YangResources.load().context();
    var base = new DefaultBaseNetconfSchemaProvider(YangResources.PARSER_FACTORY).baseSchemaForCapabilities(NetconfSessionPreferences.fromStrings(List.of("urn:ietf:params:netconf:base:1.1")));
    var tr = new NetconfMessageTransformer(DatabindContext.ofModel(ctx), true, base);
    String val = "<value><oper-status xmlns=\""+IF+"\">down</oper-status></value>";
    String tgt = "<target>/ietf-interfaces:interfaces/interface=x/oper-status</target>";
    String[][] cases = {
      {"single-edit value-last", edit("1","replace",tgt,"",val)},
      {"single-edit value-first", "<edit><edit-id>1</edit-id>"+val+"<operation>replace</operation>"+tgt+"</edit>"},
      {"single-edit value-middle", "<edit><edit-id>1</edit-id><operation>replace</operation>"+val+tgt+"</edit>"},
      {"WS single-edit value-last", edit("1","replace",tgt,"",val+"\n")},
      {"WS two-edits both value-last", edit("1","replace",tgt,"",val+"\n")+edit("2","replace",tgt,"",val+"\n")},
      {"WS+edit-ws two-edits", edit("1","replace",tgt,"",val+"\n")+"\n"+edit("2","replace",tgt,"",val+"\n")+"\n"},
      {"WS after value, create+delete", edit("1","create",tgt,"",val+"\n")+edit("2","delete",tgt,"","")},
      {"single-edit no-value(delete)", edit("1","delete",tgt,"","")},
      {"two-edits value-last,delete", edit("1","replace",tgt,"",val)+edit("2","delete",tgt,"","")},
      {"two-edits delete,value-last", edit("1","delete",tgt,"","")+edit("2","replace",tgt,"",val)},
      {"two-edits both value-last", edit("1","replace",tgt,"",val)+edit("2","replace",tgt,"",val)},
      {"two-edits both value-middle", "<edit><edit-id>1</edit-id><operation>replace</operation>"+val+tgt+"</edit><edit><edit-id>2</edit-id><operation>replace</operation>"+val+tgt+"</edit>"},
    };
    for (var c : cases) {
      String xml = "<notification xmlns=\"urn:ietf:params:xml:ns:netconf:notification:1.0\"><push-change-update xmlns=\""+YP+"\"><id>1</id><datastore-changes><yang-patch><patch-id>1</patch-id>"+c[1]+"</yang-patch></datastore-changes></push-change-update><eventTime>2026-10-05T13:21:30Z</eventTime></notification>";
      try {
        var dbf = DocumentBuilderFactory.newInstance(); dbf.setNamespaceAware(true);
        var ev = tr.toNotification(new NetconfMessage(dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))));
        System.out.println("MATRIX " + c[0] + " OK");
      } catch (Throwable t) { Throwable r = t; while (r.getCause()!=null) r=r.getCause(); System.out.println("MATRIX " + c[0] + " FAIL " + r.getMessage().replaceAll("\\s+"," ").substring(0, Math.min(160, r.getMessage().length()))); }
    }
  }
  static String edit(String id, String op, String tgt, String x, String val) { return "<edit><edit-id>"+id+"</edit-id><operation>"+op+"</operation>"+tgt+val+"</edit>"; }
}
