from cyclone_device_gateway.vmos.prerequisites import (
    CURRENT_MOBILE_BASELINE,
    CURRENT_ONE_VERSION,
    PrerequisiteSnapshot,
    VMOS_EDGE_ANDROID15_REFERENCE_IMAGE,
    VMOS_EDGE_CONTROL_API_MIN_CBS,
    VMOS_EDGE_CONTROL_API_MIN_CLIENT,
    VMOS_REMOTE_ADB_API_MAX_DAYS,
    VMOS_REMOTE_ADB_API_MIN_DAYS,
    VMOS_REMOTE_ADB_DEFAULT_HOURS,
    installation_plan,
    validate_prerequisites,
)


def _ready(**overrides):
    values = dict(
        windows_major=11,
        cyclone_one_version="1.1.2",
        adb_available=True,
        mobile_apk=r"C:\\Cyclone\\Cyclone-4.3.6.apk",
        vmos_android_major=15,
        vmos_adb_account_authorized=True,
        vmos_adb_session_open=True,
        vmos_image=None,
        needs_edge_control_api=False,
        vmos_edge_client_version=None,
        vmos_edge_cbs_version=None,
    )
    values.update(overrides)
    return PrerequisiteSnapshot(**values)


def test_install_order_is_single_clear_cloud_path():
    plan = installation_plan()

    assert [item["order"] for item in plan] == [1, 2, 3, 4, 5]
    assert [item["component"] for item in plan] == [
        "VMOS Cloud", "Cyclone One", "VMOS Remote ADB", "Cyclone Mobile", "Install + launch"
    ]
    assert CURRENT_ONE_VERSION == "1.1.2"
    assert CURRENT_MOBILE_BASELINE == "4.3.6"
    assert VMOS_REMOTE_ADB_DEFAULT_HOURS == 24
    assert (VMOS_REMOTE_ADB_API_MIN_DAYS, VMOS_REMOTE_ADB_API_MAX_DAYS) == (1, 7)
    assert "minSdk is 33" in plan[0]["reason"]
    assert "bundles CyclonePCRuntime/PC Agent" in plan[1]["reason"]
    assert "generated SSH connection command/key" in plan[2]["action"]
    assert "adb -s <serial> install -r" in plan[4]["action"]
    assert "com.cyclone.mobile/.MainActivity" in plan[4]["action"]


def test_prerequisites_accept_preferred_android15_cloud_path():
    result = validate_prerequisites(_ready())

    assert result.ready is True
    assert result.blockers == ()
    assert result.warnings == ()


def test_android13_and_14_are_supported_compatibility_targets():
    for major in (13, 14):
        result = validate_prerequisites(_ready(vmos_android_major=major))
        assert result.ready is True
        assert result.warnings == (f"Android 15 is the preferred VMOS target; Android {major} is compatibility mode.",)


def test_android10_is_rejected_by_cyclone_min_sdk():
    result = validate_prerequisites(_ready(vmos_android_major=10))

    assert result.ready is False
    assert "minSdk 33" in result.blockers[0]


def test_android16_is_not_claimed_as_validated():
    result = validate_prerequisites(_ready(vmos_android_major=16))

    assert result.ready is True
    assert result.warnings == ("Android 16 is newer than the validated VMOS 13/14/15 target set.",)


def test_remote_adb_authorization_and_live_session_are_real_blockers():
    unauthorized = validate_prerequisites(_ready(vmos_adb_account_authorized=False))
    closed = validate_prerequisites(_ready(vmos_adb_session_open=False))

    assert unauthorized.ready is False
    assert any("remote ADB permission" in item for item in unauthorized.blockers)
    assert closed.ready is False
    assert any("Local Debugging" in item for item in closed.blockers)
    assert any("SSH/ADB" in item for item in closed.blockers)


def test_edge_control_api_uses_documented_client_and_cbs_not_invented_image_floor():
    baseline = validate_prerequisites(_ready(
        needs_edge_control_api=True,
        vmos_edge_client_version=VMOS_EDGE_CONTROL_API_MIN_CLIENT,
        vmos_edge_cbs_version=VMOS_EDGE_CONTROL_API_MIN_CBS,
        vmos_image=VMOS_EDGE_ANDROID15_REFERENCE_IMAGE,
    ))
    old_client = validate_prerequisites(_ready(
        needs_edge_control_api=True,
        vmos_edge_client_version="2.0.3",
        vmos_edge_cbs_version=VMOS_EDGE_CONTROL_API_MIN_CBS,
        vmos_image=VMOS_EDGE_ANDROID15_REFERENCE_IMAGE,
    ))

    assert baseline.ready is True
    assert baseline.warnings == ()
    assert old_client.ready is False
    assert any(VMOS_EDGE_CONTROL_API_MIN_CLIENT in item for item in old_client.blockers)


def test_edge_android15_unknown_image_warns_instead_of_false_blocking():
    result = validate_prerequisites(_ready(
        needs_edge_control_api=True,
        vmos_edge_client_version=VMOS_EDGE_CONTROL_API_MIN_CLIENT,
        vmos_edge_cbs_version=VMOS_EDGE_CONTROL_API_MIN_CBS,
        vmos_image="vcloud_android15_edge_newer_official_image",
    ))

    assert result.ready is True
    assert any(VMOS_EDGE_ANDROID15_REFERENCE_IMAGE in item for item in result.warnings)


def test_missing_pc_runtime_is_expressed_as_one_install_not_two():
    result = validate_prerequisites(_ready(
        cyclone_one_version=None,
        adb_available=False,
        mobile_apk=None,
        vmos_android_major=None,
        vmos_adb_account_authorized=False,
        vmos_adb_session_open=False,
    ))

    assert result.ready is False
    assert any("Install Cyclone One 1.1.2" in item for item in result.blockers)
    assert not any("install PC Agent" in item.lower() for item in result.blockers)
