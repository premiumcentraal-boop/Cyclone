package com.cyclone.connector;
/** Reply once with {"version":1,"configRef":null|string,"state":"ready"|"degraded"|"failed"}. */
oneway interface IProfileBehaviorResult {
    void complete(String response);
}
