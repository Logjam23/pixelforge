# Graph Report - .  (2026-06-15)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 64 nodes · 97 edges · 9 communities (8 shown, 1 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 3 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7d30a651`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Main Activity UI|Main Activity UI]]
- [[_COMMUNITY_Pixel Forge Background Service|Pixel Forge Background Service]]
- [[_COMMUNITY_Service Commands and Notifications|Service Commands and Notifications]]
- [[_COMMUNITY_AI Mesh Infrastructure Overview|AI Mesh Infrastructure Overview]]
- [[_COMMUNITY_Boot Receiver|Boot Receiver]]
- [[_COMMUNITY_Chat Completion Handling|Chat Completion Handling]]

## God Nodes (most connected - your core abstractions)
1. `PixelForgeService` - 23 edges
2. `PixelForge README` - 11 edges
3. `MainActivity` - 10 edges
4. `String` - 7 edges
5. `Engine` - 5 edges
6. `BootReceiver` - 3 edges
7. `Intent` - 3 edges
8. `ApplicationCall` - 3 edges
9. `Gemma 4 E2B Model (litertlm)` - 3 edges
10. `OpenAI-Compatible API Endpoint` - 3 edges

## Surprising Connections (you probably didn't know these)
- `App Launcher Icon (hdpi)` --references--> `PixelForge README`  [INFERRED]
  app/src/main/res/mipmap-hdpi/ic_launcher.png → README.md
- `App Launcher Icon Round (hdpi)` --references--> `PixelForge README`  [INFERRED]
  app/src/main/res/mipmap-hdpi/ic_launcher_round.png → README.md
- `GitHub Actions Build Workflow` --references--> `PixelForge README`  [EXTRACTED]
  .github/workflows/build.yml → README.md

## Import Cycles
- None detected.

## Communities (9 total, 1 thin omitted)

### Community 0 - "Main Activity UI"
Cohesion: 0.19
Nodes (7): String, AppCompatActivity, Bundle, Button, MainActivity, ScrollView, TextView

### Community 1 - "Pixel Forge Background Service"
Cohesion: 0.19
Nodes (6): ApplicationEngine, File, IBinder, PixelForgeService, PowerManager, Service

### Community 2 - "Service Commands and Notifications"
Cohesion: 0.30
Nodes (4): Intent, String, Int, Notification

### Community 3 - "AI Mesh Infrastructure Overview"
Cohesion: 0.23
Nodes (12): GitHub Actions Build Workflow, App Launcher Icon (hdpi), App Launcher Icon Round (hdpi), BrainNet Home AI Mesh, Android Foreground Service, Gemma 4 E2B Model (litertlm), LiteRT-LM Android SDK, OpenAI-Compatible API Endpoint (+4 more)

### Community 4 - "Boot Receiver"
Cohesion: 0.33
Nodes (4): Intent, BroadcastReceiver, Context, BootReceiver

## Knowledge Gaps
- **16 isolated node(s):** `Context`, `Intent`, `TextView`, `ScrollView`, `Button` (+11 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PixelForgeService` connect `Pixel Forge Background Service` to `Service Commands and Notifications`, `Chat Completion Handling`?**
  _High betweenness centrality (0.151) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `PixelForge README` (e.g. with `App Launcher Icon (hdpi)` and `App Launcher Icon Round (hdpi)`) actually correct?**
  _`PixelForge README` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Context`, `Intent`, `TextView` to the rest of the system?**
  _17 weakly-connected nodes found - possible documentation gaps or missing edges._