# Search Performance Benchmark Results
- Dataset Size: 1000 (15 runs)
- Dataset Size: 5000 (10 runs)
- Dataset Size: 25000 (5 runs)
- Dataset Size: 100000 (2 runs)
## Dataset Size: 1000

| Backend | Init Time (Avg) | Avg Query Time (Mean) | Index RAM |
| :--- | :--- | :--- | :--- |
| SQLite | 168.045669ms | 10.020070ms | N/A |
| PostgreSQL | 4.973965349s | 638.358210ms | N/A |
| Redis | 7.158283125s | 596.928875ms | 0 MB |

### Detailed Query Statistics (1000)

| Query | Backend | Mean Time | Min | Max | Total Found |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Song 500` | SQLite | 11.119695ms | 9.173887ms | 26.946357ms | 1000 |
| `Song 500` | PostgreSQL | 105.911418ms | 37.460818ms | 134.755801ms | 1 |
| `Song 500` | Redis | 101.197640ms | 36.619358ms | 186.418390ms | 1 |
| | | | | | |
| `Art` | SQLite | 9.446061ms | 7.790469ms | 23.530879ms | 1000 |
| `Art` | PostgreSQL | 1.198456025s | 243.727985ms | 1.727766576s | 1000 |
| `Art` | Redis | 1.114677740s | 242.622125ms | 1.677888271s | 1000 |
| | | | | | |
| `Artist 5` | SQLite | 10.714988ms | 9.094997ms | 14.596328ms | 1000 |
| `Artist 5` | PostgreSQL | 139.192609ms | 72.326859ms | 237.993560ms | 111 |
| `Artist 5` | Redis | 133.517005ms | 67.335042ms | 342.810850ms | 111 |
| | | | | | |
| `Song -Artist1` | SQLite | 12.249920ms | 10.608412ms | 17.155618ms | 950 |
| `Song -Artist1` | PostgreSQL | 1.185918022s | 235.597111ms | 1.760138613s | 950 |
| `Song -Artist1` | Redis | 1.102934563s | 236.039813ms | 1.505387776s | 950 |
| | | | | | |
| `Artist -Song1` | SQLite | 12.571877ms | 11.237366ms | 16.343155ms | 1000 |
| `Artist -Song1` | PostgreSQL | 1.187842727s | 238.938597ms | 1.798782959s | 1000 |
| `Artist -Song1` | Redis | 1.116403072s | 234.245374ms | 1.761473594s | 1000 |
| | | | | | |
| `nonexistentterm` | SQLite | 4.017879ms | 3.355233ms | 5.431949ms | 0 |
| `nonexistentterm` | PostgreSQL | 12.828460ms | 11.240953ms | 17.039126ms | 0 |
| `nonexistentterm` | Redis | 12.843231ms | 11.054919ms | 29.590056ms | 0 |
| | | | | | |

---

## Dataset Size: 5000

| Backend | Init Time (Avg) | Avg Query Time (Mean) | Index RAM |
| :--- | :--- | :--- | :--- |
| SQLite | 1.377687723s | 32.473182ms | N/A |
| PostgreSQL | 17.191185311s | 1.149309447s | N/A |
| Redis | 29.105665183s | 1.082391256s | 0 MB |

### Detailed Query Statistics (5000)

| Query | Backend | Mean Time | Min | Max | Total Found |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Song 500` | SQLite | 34.008306ms | 32.499442ms | 38.375184ms | 5000 |
| `Song 500` | PostgreSQL | 427.862969ms | 110.090914ms | 1.566547722s | 2 |
| `Song 500` | Redis | 442.438339ms | 111.182417ms | 1.461980818s | 2 |
| | | | | | |
| `Art` | SQLite | 28.756929ms | 27.066022ms | 31.688142ms | 5000 |
| `Art` | PostgreSQL | 2.023900815s | 1.267139933s | 3.853836150s | 5000 |
| `Art` | Redis | 2.024070670s | 1.288360567s | 3.797890772s | 5000 |
| | | | | | |
| `Artist 5` | SQLite | 34.545526ms | 32.153756ms | 38.087588ms | 5000 |
| `Artist 5` | PostgreSQL | 1.137070066s | 138.459020ms | 3.700389821s | 112 |
| `Artist 5` | Redis | 766.091006ms | 141.101968ms | 3.565049124s | 112 |
| | | | | | |
| `Song -Artist1` | SQLite | 40.540152ms | 37.683971ms | 44.960322ms | 4950 |
| `Song -Artist1` | PostgreSQL | 1.671556201s | 1.298776259s | 3.560333902s | 4950 |
| `Song -Artist1` | Redis | 1.644149362s | 1.280795417s | 2.402532631s | 4950 |
| | | | | | |
| `Artist -Song1` | SQLite | 41.682935ms | 38.862239ms | 45.528382ms | 5000 |
| `Artist -Song1` | PostgreSQL | 1.619478038s | 1.276395797s | 2.396023462s | 5000 |
| `Artist -Song1` | Redis | 1.603781421s | 1.285346630s | 2.244712279s | 5000 |
| | | | | | |
| `nonexistentterm` | SQLite | 15.305247ms | 14.114671ms | 17.730158ms | 0 |
| `nonexistentterm` | PostgreSQL | 15.988595ms | 12.000304ms | 52.283471ms | 0 |
| `nonexistentterm` | Redis | 13.816743ms | 12.268744ms | 15.994601ms | 0 |
| | | | | | |

---

## Dataset Size: 25000

| Backend | Init Time (Avg) | Avg Query Time (Mean) | Index RAM |
| :--- | :--- | :--- | :--- |
| SQLite | 9.204214297s | 170.936966ms | N/A |
| PostgreSQL | 1m 17.765408051s | 816.749384ms | N/A |
| Redis | 2m 18.338139519s | 790.541126ms | 0 MB |

### Detailed Query Statistics (25000)

| Query | Backend | Mean Time | Min | Max | Total Found |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Song 500` | SQLite | 176.835803ms | 167.852707ms | 184.357958ms | 25000 |
| `Song 500` | PostgreSQL | 60.633838ms | 55.256407ms | 63.785397ms | 11 |
| `Song 500` | Redis | 61.053618ms | 56.491494ms | 64.778636ms | 11 |
| | | | | | |
| `Art` | SQLite | 152.517924ms | 141.470857ms | 162.984941ms | 25000 |
| `Art` | PostgreSQL | 1.559267285s | 1.472858457s | 1.892630125s | 25000 |
| `Art` | Redis | 1.480340074s | 1.396865109s | 1.782855358s | 25000 |
| | | | | | |
| `Artist 5` | SQLite | 177.388543ms | 165.544023ms | 196.849796ms | 25000 |
| `Artist 5` | PostgreSQL | 231.365578ms | 216.933945ms | 247.059657ms | 1111 |
| `Artist 5` | Redis | 222.629881ms | 208.794575ms | 256.839959ms | 1111 |
| | | | | | |
| `Song -Artist1` | SQLite | 210.701331ms | 196.851299ms | 229.271480ms | 24950 |
| `Song -Artist1` | PostgreSQL | 1.483271502s | 1.428853480s | 1.673967826s | 24950 |
| `Song -Artist1` | Redis | 1.472077212s | 1.406361244s | 1.641113687s | 24950 |
| | | | | | |
| `Artist -Song1` | SQLite | 218.003820ms | 204.739172ms | 234.342231ms | 25000 |
| `Artist -Song1` | PostgreSQL | 1.548545810s | 1.489438871s | 1.656355207s | 25000 |
| `Artist -Song1` | Redis | 1.489869323s | 1.411360387s | 1.802356578s | 25000 |
| | | | | | |
| `nonexistentterm` | SQLite | 90.174379ms | 85.288311ms | 101.278714ms | 0 |
| `nonexistentterm` | PostgreSQL | 17.412291ms | 16.028175ms | 18.861637ms | 0 |
| `nonexistentterm` | Redis | 17.276652ms | 15.293474ms | 20.510482ms | 0 |
| | | | | | |

---

## Dataset Size: 100000

| Backend | Init Time (Avg) | Avg Query Time (Mean) | Index RAM |
| :--- | :--- | :--- | :--- |
| SQLite | 1m 15.347350042s | 857.250026ms | N/A |
| PostgreSQL | 5m 2.486104156s | 883.971471ms | N/A |
| Redis | 8m 46.632738881s | 896.956814ms | 0 MB |

### Detailed Query Statistics (100000)

| Query | Backend | Mean Time | Min | Max | Total Found |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Song 500` | SQLite | 899.778258ms | 858.594349ms | 963.476580ms | 100000 |
| `Song 500` | PostgreSQL | 97.091953ms | 93.642220ms | 101.571571ms | 111 |
| `Song 500` | Redis | 97.992762ms | 92.680395ms | 106.682340ms | 111 |
| | | | | | |
| `Art` | SQLite | 792.828113ms | 734.903418ms | 841.770840ms | 100000 |
| `Art` | PostgreSQL | 1.395891836s | 1.337745346s | 1.557869925s | 100000 |
| `Art` | Redis | 1.368997925s | 1.297866495s | 1.449424985s | 100000 |
| | | | | | |
| `Artist 5` | SQLite | 883.443611ms | 851.047537ms | 930.639055ms | 100000 |
| `Artist 5` | PostgreSQL | 1.151451453s | 1.056675632s | 1.349634301s | 11111 |
| `Artist 5` | Redis | 1.150784942s | 1.094192751s | 1.205125909s | 11111 |
| | | | | | |
| `Song -Artist1` | SQLite | 1.028178389s | 962.724984ms | 1.086592426s | 99950 |
| `Song -Artist1` | PostgreSQL | 1.257743730s | 1.239864358s | 1.289734228s | 99950 |
| `Song -Artist1` | Redis | 1.352480049s | 1.287111992s | 1.426466406s | 99950 |
| | | | | | |
| `Artist -Song1` | SQLite | 1.101588402s | 1.019216308s | 1.406372803s | 100000 |
| `Artist -Song1` | PostgreSQL | 1.368383631s | 1.324325511s | 1.420989779s | 100000 |
| `Artist -Song1` | Redis | 1.370603290s | 1.347137002s | 1.397583910s | 100000 |
| | | | | | |
| `nonexistentterm` | SQLite | 437.683387ms | 416.617869ms | 462.376469ms | 0 |
| `nonexistentterm` | PostgreSQL | 33.266223ms | 31.206531ms | 34.612151ms | 0 |
| `nonexistentterm` | Redis | 40.881920ms | 31.412745ms | 112.211603ms | 0 |
| | | | | | |

---

## Scaling Analysis (Mean Latency)

| Backend | 1k -> 100k Latency Increase |
| :--- | :--- |
| SQLite | 85.55x growth (for 100x data) |
| PostgreSQL | 1.38x growth (for 100x data) |
| Redis | 1.50x growth (for 100x data) |
