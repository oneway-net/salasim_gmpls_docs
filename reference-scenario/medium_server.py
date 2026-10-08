#!/usr/bin/env python3
"""The cables of the reference scenario: one link plane per device, served from one process.

The medium contract (salasim-medium) is per device: GET <base>/ports and GET <base>/ports/stream give the ports of
that device only. Every node is given MEDIUM_BASE_URL=http://<this host>:<port>/<node name>, so each node name is
mounted as its own link plane. A port is named like the device names its interface (the interface id).

A fiber cut is carrier down on BOTH ends: PUT /<node>/ports/<ifId> {"carrier": "down"} on each end (see fault.sh).
Needs fastapi and uvicorn; the link plane itself is salasim_sim.linkplane (backend repo, on PYTHONPATH).
"""
import json
import os

import uvicorn
from fastapi import FastAPI

from salasim_sim.linkplane import LinkPlane, create_app

scenario = json.load(open(os.environ.get("SCENARIO", "/scenario/scenario.json")))
root = FastAPI(title="salasim reference scenario: link planes")
planes = {}
for node in scenario["nodes"]:
    plane = LinkPlane()
    planes[node["name"]] = plane
    root.mount("/" + node["name"], create_app(plane))
for link in scenario["links"]:
    # every port starts with carrier: the first state of a port with carrier is not a change for the device
    planes[link["a"]].set_port(str(link["aIf"]), carrier="up", delay_us=link["delayUs"])
    planes[link["b"]].set_port(str(link["bIf"]), carrier="up", delay_us=link["delayUs"])


@root.get("/health")
def health():
    return {"nodes": sorted(planes)}


if __name__ == "__main__":
    uvicorn.run(root, host="0.0.0.0", port=int(os.environ.get("MEDIUM_PORT", scenario["ports"]["medium"])))
