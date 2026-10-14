# D7 spike

Machine: v1.36.5+k3s1 on Ubuntu 22.04.5 LTS, 1 node(s), 6 cpu, 15.1 GiB
Images: {'pce': 'salasim/pce:spike', 'controller': 'salasim/controller:spike', 'emulator': 'salasim/emulator:spike', 'platform': 'salasim/platform:spike'}

## Loading, per size

| nodes | domains | pods | provision s | load s | reset s | reload s | slowest component |
|---|---|---|---|---|---|---|---|
| 10 | 3 | 19 | 13.8 | 11.8 | 38.2 | 12.2 | pnc-d3 |
| 20 | 3 | 29 | 10.2 | 21.1 | 43.7 | 20.6 | node-10-3-0-7 |
| 30 | 3 | 39 | 10.8 | 33.0 | 47.2 | 31.4 | node-10-3-0-8 |

## Model

load seconds = a + b x nodes: a = 0.8, b = 1.060 per node (r2 = 1.00)

## Memory per pod (MiB, after settling)

- core: 139
- core-db: 62
- emulator: 141
- linkplane: 36
- pce: 170
- pnc: 202

## What to set

Quotas per class (from the memory above, margin 1.3):

- S: `{'pods': '51', 'requests.memory': '7263Mi'}`
- M: `{'pods': '168', 'requests.memory': '23779Mi'}`

Price book allowances (`SALASIM_PRICING`):

- S (30 nodes): expected load 32.5 s -> loadLowSeconds 23, loadHighSeconds 65
- M (120 nodes): expected load 127.9 s -> loadLowSeconds 90, loadHighSeconds 256

## Where the time goes (share of the slowest component, biggest case)

- node-10-3-0-8: 100%
- node-10-3-0-7: 100%
- node-10-3-0-6: 100%
- node-10-3-0-5: 100%
- node-10-3-0-4: 100%
- node-10-3-0-3: 100%
- node-10-3-0-2: 100%
- node-10-3-0-1: 100%
- node-10-2-0-8: 100%
- node-10-2-0-7: 100%
- node-10-2-0-6: 100%
- node-10-2-0-5: 100%
- node-10-2-0-4: 100%
- node-10-1-0-7: 100%
- node-10-1-0-14: 100%
- node-10-2-0-2: 83%
- node-10-1-0-9: 83%
- node-10-1-0-5: 83%
- node-10-1-0-3: 83%
- node-10-1-0-2: 83%
- node-10-2-0-3: 55%
- node-10-2-0-1: 55%
- node-10-1-0-8: 55%
- node-10-1-0-6: 55%
- node-10-1-0-4: 55%
- pnc-d3: 49%
- pce-d2: 49%
- node-10-1-0-11: 42%
- node-10-1-0-1: 42%
- core: 42%
- node-10-1-0-13: 35%
- node-10-1-0-12: 35%
- linkplane: 35%
- pnc-d2: 28%
- pce-d3: 28%
- node-10-1-0-10: 28%
- pce-d1: 22%
- pnc-d1: 16%
- core-db: 1%

## Residue after a reset and after a clean

- (none found)
