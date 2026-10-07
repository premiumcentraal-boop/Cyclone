package com.cyclone.connector;
import com.cyclone.connector.IProfileBehaviorResult;
/** cyclone.profile-startup/1. JSON event; optional opaque reference in the result. */
oneway interface IProfileBehaviorProvider {
    void beforeLaunch(String event, IProfileBehaviorResult result);
}
