# OpenMaps Scout

Scout is OpenMaps' embedded navigation agent. It is one agent with specialized capabilities, not a collection of independent agents.

## Product rule

Scout may interpret intent, choose tools, summarize verified results, and coordinate actions. It may not manufacture navigation facts.

Authoritative facts stay in the existing OpenMaps subsystems:

- Geocoding resolves destinations.
- Valhalla calculates routes and maneuvers.
- OSM/Overpass-backed Places supplies nearby places and parking.
- Road Intelligence supplies verified hazards, weather, and speed-limit data when available.
- Environment profiles adapt navigation presentation.
- AR/Live renders route guidance.

An AI reasoner must never generate route geometry, lane guidance, hazards, traffic conditions, parking availability, or AR anchors.

## Capability roadmap

### Scout 1 - Trip Intent + Arrival Intelligence

Foundation:
- Navigate to ...
- Plan my arrival to ...
- Find parking
- Find category nearby
- Explain route
- typed Scout actions
- central grounding checks
- deterministic local intent parser as an offline-safe fallback

### Scout 2 - Remote language reasoner

Put any model behind an OpenMaps server endpoint. Never ship an AI provider secret in the APK.
The model returns only a typed intent/tool plan. Android validates it and executes OpenMaps tools.

### Scout 3 - Verified live road assistant

Summarize already-grounded Road Intelligence for the active route. Never infer hazards from camera imagery.

### Scout 4 - Grounded visual/AR assistance

Camera understanding may identify scene objects only when confidence and device capability pass a quality gate. It must never override routing truth.

## Beta data rule

No Foursquare or paid POI dependency. Use OSM/Overpass + OpenMaps routing. Rich fields are optional and are shown only when providers actually supply them.
