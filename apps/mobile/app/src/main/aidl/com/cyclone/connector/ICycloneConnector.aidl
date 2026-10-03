// Plan 51: contract cyclone.connector/1. One call, JSON in and out, so the contract grows by adding methods and
// fields, never by changing this interface. See tools/cyclone-connector-sdk/SPEC.md.
package com.cyclone.connector;

interface ICycloneConnector {
    /** {"method": "...", "args": {...}} -> {"ok": true, "result": ...} or {"ok": false, "error": {"code", "message"}} */
    String call(String request);
}
