// Contract cyclone.connector/1: JSON methods grow additively. Minor 1 appends provider registration;
// the original call transaction is preserved. See tools/cyclone-connector-sdk/SPEC.md.
package com.cyclone.connector;

import com.cyclone.connector.IProfileBehaviorProvider;

interface ICycloneConnector {
    /** {"method": "...", "args": {...}} -> {"ok": true, "result": ...} or {"ok": false, "error": {"code", "message"}} */
    String call(String request);
    /** Added in minor 1. Null unregisters. Request: {"version":1}. */
    String registerProfileProvider(String request, IProfileBehaviorProvider provider);
}
