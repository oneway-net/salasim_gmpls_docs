#!/usr/bin/env python3
"""Renders every runtime file of the reference scenario from scenario.json into build/.

    render.py [--out DIR] [--scenario FILE]

Nothing here is simulation: these are the files an operator would write for a small real network (domain topologies,
interface inventories, controller inventories) plus a docker compose file that plays the role of the racks and the
cables. Only the standard library is used.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import shutil

HERE = pathlib.Path(__file__).resolve().parent
IMAGE_TAG = "ref"


def load(path: pathlib.Path) -> dict:
    s = json.loads(path.read_text())
    s["_nodes"] = {n["name"]: n for n in s["nodes"]}
    s["_domains"] = {d["id"]: d for d in s["domains"]}
    return s


def link_ids(s: dict, link: dict) -> dict:
    """Both ends in a stable order and the ids derived from them."""
    a, b = link["a"], link["b"]
    na, nb = s["_nodes"][a], s["_nodes"][b]
    inter = na["domain"] != nb["domain"]
    first, second = sorted([a, b])  # the fault target id names the ends in name order
    target = f"{link['kind']}:{first}|{second}"
    return {"target": target, "inter": inter}


def interfaces(s: dict) -> dict:
    """node name -> list of {ifId, peer, type, planeId, faultTargetId}; the compiler's interface inventory format."""
    out = {n["name"]: [] for n in s["nodes"]}
    for link in s["links"]:
        ids = link_ids(s, link)
        for me, my_if, other in ((link["a"], link["aIf"], link["b"]), (link["b"], link["bIf"], link["a"])):
            out[me].append({"ifId": my_if, "peer": s["_nodes"][other]["routerId"], "type": link["kind"],
                            "planeId": "", "faultTargetId": ids["target"]})
    for name in out:
        out[name].sort(key=lambda e: e["ifId"])
    return out


def interfaces_json(s: dict) -> dict:
    ifs = interfaces(s)
    return {"nodes": {s["_nodes"][name]["routerId"]: entries for name, entries in ifs.items()}}


def domain_nodes(s: dict, domain: str) -> list[dict]:
    return [n for n in s["nodes"] if n["domain"] == domain]


def domain_topology_json(s: dict, domain: str) -> dict:
    """The domain PCE's actual topology (TedbJsonLoader.loadDomain): intra links once per physical link."""
    d = s["_domains"][domain]
    intra, inter = [], []
    for link in s["links"]:
        na, nb = s["_nodes"][link["a"]], s["_nodes"][link["b"]]
        ids = link_ids(s, link)
        delay_ms = link["delayUs"] / 1000.0
        bw = s.get("bandwidthBps", 1_000_000_000)
        if na["domain"] == domain and nb["domain"] == domain:
            intra.append({"fromNode": na["routerId"], "fromInterface": str(link["aIf"]), "toNode": nb["routerId"],
                          "toInterface": str(link["bIf"]), "physicalLinkId": f"{link['a']}|{link['b']}",
                          "faultTargetId": ids["target"], "delayMs": delay_ms, "maxBandwidthBps": bw})
        elif domain in (na["domain"], nb["domain"]):
            local, remote = (na, nb) if na["domain"] == domain else (nb, na)
            local_if = link["aIf"] if local is na else link["bIf"]
            remote_if = link["bIf"] if local is na else link["aIf"]
            inter.append({"localDomainId": d["pceDomainId"], "localNode": local["routerId"],
                          "localInterface": str(local_if),
                          "remoteDomainId": s["_domains"][remote["domain"]]["pceDomainId"],
                          "remoteNode": remote["routerId"], "remoteInterface": str(remote_if),
                          "physicalLinkId": f"{link['a']}|{link['b']}", "faultTargetId": ids["target"],
                          "delayMs": delay_ms, "maxBandwidthBps": bw})
    return {"domainId": d["pceDomainId"], "nodes": [{"id": n["routerId"]} for n in domain_nodes(s, domain)],
            "intraDomainLinks": intra, "interDomainLinks": inter,
            "reachability": [{"domainId": d["pceDomainId"], "prefixes": [d["reachability"]]}]}


def domain_topology_xml(s: dict, domain: str) -> str:
    d = s["_domains"][domain]
    net, prefix = d["reachability"].split("/")
    out = ['<?xml version="1.0" encoding="UTF-8"?>', "<network>", "\t<domain>", '\t\t<layer type="mpls" ></layer>',
           f"\t\t<domain_id>{d['pceDomainId']}</domain_id>", "\t\t<reachability_entry>",
           f"\t\t\t<ipv4_address>{net}</ipv4_address>", f"\t\t\t<prefix>{prefix}</prefix>", "\t\t</reachability_entry>"]
    for n in domain_nodes(s, domain):
        out.append(f"\t\t<node><router_id>{n['routerId']}</router_id></node>")
    # Label-space defaults, as the Backend's scenario compiler emits them (_append_edge_common): vestigial for MPLS, but
    # MPLS_CrossSnapshot_AlgorithmPreComputation.setTEDB dereferences the WSON information unguarded, and without it the
    # algorithm is left with no precomputation and every request fails with NPE.
    out += ["\t\t<edgeCommon>", "\t\t\t<AvailableLabels>", '\t\t\t\t<LabelSetField type="4">',
            "\t\t\t\t\t<numLabels>100</numLabels>", '\t\t\t\t\t<baseLabel grid="3" cs="5" n="0"></baseLabel>',
            "\t\t\t\t</LabelSetField>", "\t\t\t</AvailableLabels>", "\t\t</edgeCommon>"]
    bw_mbps = s.get("bandwidthBps", 1_000_000_000) // 1_000_000
    for link in s["links"]:
        na, nb = s["_nodes"][link["a"]], s["_nodes"][link["b"]]
        if not (na["domain"] == domain and nb["domain"] == domain):
            continue
        for src, sif, dst, dif in ((na, link["aIf"], nb, link["bIf"]), (nb, link["bIf"], na, link["aIf"])):
            out += ['\t\t<edge type="intradomain">',
                    f"\t\t\t<source><router_id>{src['routerId']}</router_id><if_id>{sif}</if_id></source>",
                    f"\t\t\t<destination><router_id>{dst['routerId']}</router_id><if_id>{dif}</if_id></destination>",
                    f"\t\t\t<delay>{link['delayUs'] / 1000.0:g}</delay>",
                    f"\t\t\t<maximum_bandwidth>{bw_mbps}</maximum_bandwidth>",
                    f'\t\t\t<unreserved_bandwidth priority="0">{bw_mbps}</unreserved_bandwidth>', "\t\t</edge>"]
    out += ["\t</domain>", "</network>", ""]
    return "\n".join(out)


def domain_native_json(s: dict, domain: str) -> dict:
    """The PNC's native network: RFC 9195 instance data of an ietf-te-topology network."""
    d = s["_domains"][domain]
    bw_bytes = str(s.get("bandwidthBps", 1_000_000_000) // 8)
    nodes, links = [], []
    ifs = interfaces(s)
    for n in domain_nodes(s, domain):
        tps = []
        for e in ifs[n["name"]]:
            tp = {"tp-id": str(e["ifId"]), "ietf-te-topology:te-tp-id": e["ifId"]}
            peer = next(x for x in s["nodes"] if x["routerId"] == e["peer"])
            if peer["domain"] != domain:
                link = next(l for l in s["links"] if link_ids(s, l)["target"] == e["faultTargetId"])
                tp["ietf-te-topology:te"] = {
                    "inter-domain-plug-id": base64.b64encode(e["faultTargetId"].encode()).decode(),
                    "oper-status": "up",
                    "salasim-actn:inter-domain-link": {"physical-link-id": e["faultTargetId"],
                                                       "delay": link["delayUs"], "max-bandwidth": bw_bytes}}
            tps.append(tp)
        nodes.append({"node-id": n["routerId"], "ietf-te-topology:te-node-id": n["routerId"],
                      "ietf-te-topology:te": {"te-node-attributes": {"domain-id": d["number"]}},
                      "ietf-network-topology:termination-point": tps})
    for link in s["links"]:
        na, nb = s["_nodes"][link["a"]], s["_nodes"][link["b"]]
        if not (na["domain"] == domain and nb["domain"] == domain):
            continue
        target = link_ids(s, link)["target"]
        ends = {na["routerId"]: (na, link["aIf"]), nb["routerId"]: (nb, link["bIf"])}
        src_id = min(ends)  # fwd: the end with the smaller node-id is the source
        dst_id = max(ends)
        for suffix, s_id, d_id in (("fwd", src_id, dst_id), ("rev", dst_id, src_id)):
            links.append({"link-id": f"{target}:{suffix}",
                          "source": {"source-node": s_id, "source-tp": str(ends[s_id][1])},
                          "destination": {"dest-node": d_id, "dest-tp": str(ends[d_id][1])},
                          "ietf-te-topology:te": {"te-link-attributes": {
                              "te-delay-metric": link["delayUs"],
                              "max-link-bandwidth": {"te-bandwidth": {"generic": bw_bytes}}}}})
    network = {"network-id": f"salasim:{domain}/native", "network-types": {"ietf-te-topology:te-topology": {}},
               "node": nodes, "ietf-network-topology:link": links}
    return {"ietf-yang-instance-data:instance-data-set": {
        "name": f"{domain}-native",
        "content-schema": {"module": ["ietf-network@2018-02-26", "ietf-network-topology@2018-02-26",
                                      "ietf-te-topology@2020-08-06", "salasim-actn@2026-10-06"]},
        "content-data": {"ietf-network:networks": {"network": [network]}}}}


def inventory(role: str, domain: str, devices: list[dict]) -> dict:
    content = {"deployment-id": "ref", "role": role, "device": devices}
    if domain:
        content["domain-id"] = domain
    return {"ietf-yang-instance-data:instance-data-set": {
        "name": "salasim-inventory", "content-schema": {"module": "salasim-actn@2026-10-06"},
        "description": f"Inventory of the {role} {domain or ''} of the reference scenario".strip(),
        "content-data": {"salasim-actn:inventory": content}}}


def addresses(s: dict) -> dict:
    """container name -> fixed address (node router ids are real addresses on the compose network)."""
    a = {n["name"]: n["routerId"] for n in s["nodes"]}
    a.update({"parent-pce": "10.77.9.10", "pce-d1": "10.77.9.11", "pce-d2": "10.77.9.12", "pnc-d1": "10.77.9.21",
              "pnc-d2": "10.77.9.22", "mdsc": "10.77.9.30", "medium": "10.77.9.40"})
    return a


def inventories(s: dict) -> dict[str, dict]:
    ad = addresses(s)
    ssh = s["ports"]["netconfSsh"]
    out = {}
    for d in s["domains"]:
        devices = [{"device-id": f"ref-pce-{d['id']}", "kind": "pce", "domain-id": d["id"], "host": ad[f"pce-{d['id']}"],
                    "port": ssh}]
        for n in domain_nodes(s, d["id"]):
            devices.append({"device-id": f"ref-{n['name']}", "kind": "node", "domain-id": d["id"],
                            "host": ad[n["name"]], "port": ssh, "router-id": n["routerId"]})
        out[f"pnc-{d['id']}"] = inventory("pnc", d["id"], devices)
    mdsc = [{"device-id": "ref-pce-core", "kind": "pce", "domain-id": "core", "host": ad["parent-pce"], "port": ssh}]
    for d in s["domains"]:
        mdsc.append({"device-id": f"ref-pnc-{d['id']}", "kind": "pnc", "domain-id": d["id"],
                     "host": ad[f"pnc-{d['id']}"], "port": ssh})
    out["mdsc"] = inventory("mdsc", "", mdsc)
    return out


def expected(s: dict) -> dict:
    nodes = s["_nodes"]
    fault = s["fault"]["link"]
    ifs = interfaces(s)
    fault_ports = {}
    for name, other in ((fault[0], fault[1]), (fault[1], fault[0])):
        peer_ip = nodes[other]["routerId"]
        fault_ports[name] = next(str(e["ifId"]) for e in ifs[name] if e["peer"] == peer_ip)
    svc = s["service"]
    ports = s["ports"]
    return {
        "faultTarget": next(link_ids(s, l)["target"] for l in s["links"] if {l["a"], l["b"]} == set(fault)),
        "faultPorts": fault_ports,
        "service": {**svc, "symbolicPathName": f"service/{svc['serviceId']}/tunnel/{svc['canonicalTunnelId']}",
                    "sourceRouterId": nodes[svc["source"]]["routerId"],
                    "destinationRouterId": nodes[svc["destination"]]["routerId"],
                    "expectedPathRouterIds": [nodes[n]["routerId"] for n in svc["expectedPath"]],
                    "expectedPathAfterFaultRouterIds": [nodes[n]["routerId"] for n in svc["expectedPathAfterFault"]]},
        "hostPorts": {"parentApi": 18080, "pceD1Api": 18081, "pceD2Api": 18082, "medium": 18099,
                      "mdsc": 18181, "nodeApiBase": 18100},
        "nodes": [n["name"] for n in s["nodes"]],
    }


def compose(s: dict) -> str:
    ad = addresses(s)
    p = s["ports"]
    pw = s["netconfPassword"]
    nodes_by_domain = {d["id"]: domain_nodes(s, d["id"]) for d in s["domains"]}
    L = ["name: salasim-ref", "", "networks:", "  ref:", "    driver: bridge", "    ipam:", "      config:",
         f"        - subnet: {s['subnet']}", f"          gateway: {s['gateway']}", "", "volumes:", "  shared: {}", "",
         "services:"]

    def svc(name, image, extra, environment=None, ports=None, user=None, caps=None, volumes=None, command=None):
        L.append(f"  {name}:")
        L.append(f"    image: {image}")
        L.append("    restart: \"no\"")
        if user:
            L.append(f"    user: \"{user}\"")
        if command:
            L.append(f"    command: {command}")
        if caps:
            L.append("    cap_add: [" + ", ".join(caps) + "]")
        L.append("    networks:")
        L.append("      ref:")
        L.append(f"        ipv4_address: {ad[name]}")
        if environment:
            L.append("    environment:")
            for k, v in environment.items():
                L.append(f"      {k}: \"{v}\"")
        if ports:
            L.append("    ports:")
            for hp, cp in ports:
                L.append(f"      - \"127.0.0.1:{hp}:{cp}\"")
        vols = ["./:/scenario:ro"] + (volumes or [])
        L.append("    volumes:")
        for v in vols:
            L.append(f"      - {v}")
        L.extend(extra or [])
        L.append("")

    svc("medium", "docker.m.daocloud.io/library/python:3.12-slim", None,
        environment={"PYTHONPATH": "/backend-src", "MEDIUM_PORT": str(p["medium"]), "SCENARIO": "/scenario/scenario.json",
                     "PIP_ROOT_USER_ACTION": "ignore"},
        ports=[(18099, p["medium"])],
        volumes=[f"{HERE.parent.parent / 'salasim_gmpls_backend' / 'src'}:/backend-src:ro",
                 f"{HERE / 'medium_server.py'}:/medium_server.py:ro"],
        command='["sh", "-c", "pip install --quiet fastapi uvicorn pydantic && python /medium_server.py"]')
    for i, n in enumerate(s["nodes"]):
        d = n["domain"]
        pce = f"pce-{d}"
        svc(n["name"], f"salasim/emulator:{IMAGE_TAG}", None, user="0:0", caps=["NET_RAW"], ports=[(18101 + i, p["nodeApi"])],
            environment={
                "EMULATOR_DEFAULT_TEMPLATE": "/scenario/templates/emulator-default.properties.tpl",
                "EMULATOR_NODE_TEMPLATE": "/scenario/templates/emulator-node.properties.tpl",
                "NODE_HOSTNAME": "emulator-0", "NODE_ID": n["routerId"], "LOCAL_NODE_ADDRESS": n["routerId"],
                "PCE_ADDRESS": ad[pce], "PCEP_PORT": str(p["pcep"]), "NODE_API_PORT": str(p["nodeApi"]),
                "NODE_PCEP_SERVER_PORT": str(p["nodePcepServer"]), "TOTAL_TOPOLOGY_NUMS": "1",
                "EMULATOR_TOPOLOGY_AWARE": "false",
                "INTERFACE_INVENTORY_FILE": "/scenario/interfaces.json",
                "MEDIUM_BASE_URL": f"http://{ad['medium']}:{p['medium']}/{n['name']}",
                "NODE_NETCONF_SSH_PASSWORD": pw,
                "JAVA_OPTS": ("-Xms128m -Xmx384m -Dnode.legacy.management.enabled=false "
                              "-Dnode.legacy.fastPcep.enabled=false -Dnode.legacy.remoteInitiate.enabled=false "
                              "-Dsalasim.emulator.rsvpSetup.timeoutMs=30000 -Dsalasim.emulator.tunnelEstablish.timeoutMs=30000 "
                              "-Dsalasim.emulator.pathComputation.timeoutMs=30000")})
    for i, d in enumerate(s["domains"]):
        did = d["id"]
        svc(f"pce-{did}", f"salasim/pce:{IMAGE_TAG}", None, ports=[(18081 + i, p["pceApi"])],
            environment={
                "PCE_MODE": "domain", "CONFIG_TEMPLATE": "/scenario/templates/pce-domain.xml.tpl",
                "DEPLOYMENT_ID": "ref", "DOMAIN_ID": d["pceDomainId"], "PCE_MULTIDOMAIN": "true",
                "TOTAL_TOPOLOGY_NUMS": "2", "PCE_SERVER_PORT": str(p["pcep"]), "PCE_API_PORT": str(p["pceApi"]),
                "PARENT_PCE_ADDRESS": ad["parent-pce"], "PARENT_PCE_PORT": str(p["parentPcep"]),
                "LOCAL_PCE_ADDRESS_FOR_PARENT": ad[f"pce-{did}"],
                "DOMAIN_TOPOLOGY_FILE": f"/scenario/domains/{did}/topology.xml",
                "ACTUAL_TOPOLOGY_FILE": f"/scenario/domains/{did}/topology.json",
                # the PNC reports link state for the network salasim:<domain id of the inventory>
                "ACTUAL_TOPOLOGY_NETWORK_ID": f"salasim:{did}",
                # fail-closed PCC admission: only the router ids of this domain's nodes may open a session
                "PCC_ROUTER_INVENTORY_FILE": f"/scenario/domains/{did}/pcc-router-ids.json",
                "PCE_NETCONF_SSH_PASSWORD": pw,
                "JAVA_OPTS": "-Xms128m -Xmx512m" + (" -Dlog4j.configurationFile=/scenario/log4j2-debug.xml"
                                                    if os.environ.get("REF_PCE_DEBUG") else "")})
    svc("parent-pce", f"salasim/pce:{IMAGE_TAG}", None, ports=[(18080, p["pceApi"])],
        volumes=["shared:/var/salasim/shared:ro"],
        environment={
            "PCE_MODE": "parent", "CONFIG_TEMPLATE": "/scenario/templates/pce-parent.xml.tpl", "DEPLOYMENT_ID": "ref",
            "TOTAL_TOPOLOGY_NUMS": "2", "PARENT_PCE_SERVER_PORT": str(p["parentPcep"]), "PCE_API_PORT": str(p["pceApi"]),
            "PARENT_PCE_ADDRESS": "0.0.0.0", "PARENT_TOPOLOGY_FILE": "/var/salasim/shared/composed-topology.json",
            "CHILD_1_SERVICE": ad["pce-d1"], "CHILD_1_DOMAIN_ID": s["domains"][0]["pceDomainId"],
            "CHILD_2_SERVICE": ad["pce-d2"], "CHILD_2_DOMAIN_ID": s["domains"][1]["pceDomainId"],
            "PCE_NETCONF_SSH_PASSWORD": pw,
                "JAVA_OPTS": "-Xms128m -Xmx512m" + (" -Dlog4j.configurationFile=/scenario/log4j2-debug.xml"
                                                    if os.environ.get("REF_PCE_DEBUG") else "")})
    for d in s["domains"]:
        did = d["id"]
        svc(f"pnc-{did}", f"salasim/controller:{IMAGE_TAG}", None, command="pnc",
            environment={
                "SALASIM_CONTROLLER_INVENTORY_FILE": f"/scenario/inventories/pnc-{did}.json",
                "SALASIM_CONTROLLER_PNC_INTERFACES_FILE": "/scenario/interfaces.json",
                "SALASIM_CONTROLLER_PNC_NATIVE_TOPOLOGY_FILE": f"/scenario/domains/{did}/native.json",
                "SALASIM_CONTROLLER_NETCONF_PASSWORD_FILE": "/scenario/credentials/netconf-password",
                "SALASIM_CONTROLLER_MPI_SSH_PASSWORD": pw, "JAVA_OPTS": "-Xms128m -Xmx512m"})
    svc("mdsc", f"salasim/controller:{IMAGE_TAG}", None, command="mdsc", user="0:0", ports=[(18181, p["mdscRestconf"])],
        volumes=["shared:/var/salasim/shared"],
        environment={
            "SALASIM_CONTROLLER_INVENTORY_FILE": "/scenario/inventories/mdsc.json",
            "SALASIM_CONTROLLER_NETCONF_PASSWORD_FILE": "/scenario/credentials/netconf-password",
            "SALASIM_CONTROLLER_MDSC_COMPOSED_TOPOLOGY_FILE": "/var/salasim/shared/composed-topology.json",
            "SALASIM_CONTROLLER_RESTCONF_BIND": "0.0.0.0",
            # the community RESTCONF has no authentication: lab only, the port is published on 127.0.0.1 only
            "SALASIM_CONTROLLER_RESTCONF_ALLOW_REMOTE": "true", "JAVA_OPTS": "-Xms128m -Xmx512m"})
    return "\n".join(L)


def write(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE / "build"))
    ap.add_argument("--scenario", default=str(HERE / "scenario.json"))
    args = ap.parse_args()
    out = pathlib.Path(args.out)
    s = load(pathlib.Path(args.scenario))
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    shutil.copytree(HERE / "templates", out / "templates")
    shutil.copy(pathlib.Path(args.scenario), out / "scenario.json")
    write(out / "interfaces.json", json.dumps(interfaces_json(s), indent=2) + "\n")
    for d in s["domains"]:
        did = d["id"]
        write(out / "domains" / did / "topology.json", json.dumps(domain_topology_json(s, did), indent=2) + "\n")
        write(out / "domains" / did / "topology.xml", domain_topology_xml(s, did))
        write(out / "domains" / did / "pcc-router-ids.json",
              json.dumps([{"router_id": n["routerId"]} for n in domain_nodes(s, did)], indent=2) + "\n")
        write(out / "domains" / did / "native.json", json.dumps(domain_native_json(s, did), indent=2) + "\n")
    for name, inv in inventories(s).items():
        write(out / "inventories" / f"{name}.json", json.dumps(inv, indent=2) + "\n")
    write(out / "credentials" / "netconf-password", s["netconfPassword"])
    if os.environ.get("REF_PCE_DEBUG"):
        # the PCE's own logging configuration with the PCEServer logger at DEBUG (routing no-path diagnosis, PCEP detail)
        base = (HERE.parent.parent / "salasim_gmpls_pce" / "src" / "main" / "resources" / "log4j2.xml").read_text()
        write(out / "log4j2-debug.xml", base.replace('name="PCEServer" level="INFO"', 'name="PCEServer" level="DEBUG"'))
    write(out / "expected.json", json.dumps(expected(s), indent=2) + "\n")
    write(out / "docker-compose.yml", compose(s) + "\n")
    print(f"rendered {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
