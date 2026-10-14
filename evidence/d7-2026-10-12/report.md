# D7 spike

Machine: v1.36.5+k3s1 on Ubuntu 22.04.5 LTS, 1 node(s), 6 cpu, 15.1 GiB
Images: {'pce': 'salasim/pce:spike', 'controller': 'salasim/controller:spike', 'emulator': 'salasim/emulator:spike', 'platform': 'salasim/platform:spike'}

## Loading, per size

| nodes | domains | pods | provision s | load s | reset s | reload s | slowest component |
|---|---|---|---|---|---|---|---|
| 10 | 3 | 19 | 30.2 | 16.1 | 38.3 | 15.9 | node-10-3-0-2 |
| 20 | 3 | 29 | 10.4 | 27.1 | 42.4 | 27.7 | node-10-3-0-7 |
| 30 | 3 | 39 | 11.2 | 39.6 | 46.7 | 39.9 | node-10-3-0-8 |

## Model

load seconds = a + b x nodes: a = 4.0, b = 1.179 per node (r2 = 1.00)

## Memory per pod (MiB, after settling)

- core: 164
- core-db: 67
- emulator: 147
- linkplane: 36
- pce: 175
- pnc: 211

## What to set

Quotas per class (from the memory above, margin 1.3):

- S: `{'pods': '51', 'requests.memory': '7605Mi'}`
- M: `{'pods': '168', 'requests.memory': '24857Mi'}`

Price book allowances (`SALASIM_PRICING`):

- S (30 nodes): expected load 39.4 s -> loadLowSeconds 28, loadHighSeconds 79
- M (120 nodes): expected load 145.5 s -> loadLowSeconds 102, loadHighSeconds 291

## Where the time goes (share of the slowest component, biggest case)

- node-10-3-0-8: 100%
- node-10-3-0-7: 100%
- node-10-3-0-6: 100%
- node-10-3-0-5: 100%
- node-10-3-0-4: 100%
- node-10-3-0-2: 100%
- node-10-3-0-3: 93%
- node-10-2-0-8: 93%
- node-10-2-0-7: 93%
- node-10-2-0-6: 93%
- node-10-2-0-5: 93%
- node-10-2-0-4: 93%
- node-10-2-0-3: 93%
- node-10-2-0-2: 93%
- node-10-3-0-1: 82%
- node-10-1-0-4: 82%
- node-10-1-0-3: 82%
- node-10-1-0-2: 82%
- node-10-1-0-14: 82%
- node-10-1-0-9: 73%
- node-10-1-0-8: 73%
- node-10-1-0-7: 73%
- node-10-1-0-6: 65%
- node-10-1-0-5: 65%
- pnc-d3: 57%
- pnc-d2: 57%
- pce-d3: 57%
- node-10-2-0-1: 57%
- node-10-1-0-11: 57%
- linkplane: 57%
- node-10-1-0-12: 47%
- node-10-1-0-10: 47%
- pce-d2: 40%
- node-10-1-0-1: 40%
- node-10-1-0-13: 32%
- core: 32%
- pnc-d1: 25%
- pce-d1: 25%
- core-db: 1%

## Residue after a reset and after a clean

- after a reset of 10 nodes: volume pvc-7915a85a-5980-40c8-97cb-ee3d40aa5c0f (Bound) of data-core-db-0 is still there
- after a reset of 20 nodes: volume pvc-7b1b2f6d-4f67-435e-a616-d4c2fe0318e3 (Bound) of data-core-db-0 is still there
- after a reset of 30 nodes: volume pvc-59e5dad9-d919-4468-8610-67a85eb1ddb2 (Bound) of data-core-db-0 is still there
