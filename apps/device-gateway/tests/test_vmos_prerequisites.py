from cyclone_device_gateway.vmos.prerequisites import (
    CURRENT_MOBILE_BASELINE,
    CURRENT_ONE_VERSION,
    EDGE_ANDROID15_CONTROL_API_MIN_IMAGE,
    PrerequisiteSnapshot,
    VMOS_REMOTE_ADB_SESSION_HOURS,
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
    )
    values.update(overrides)
    return PrerequisiteSnapshot(**values)


def test_install_order_is_single_clear_path():
    plan = installation_plan()

    assert [item["order"] for item in plan] == [1, 2, 3, 4, 5]
    assert [item["component"] for item in plan] == [
        "VMOS", "Cyclone One", "VMOS Remote ADB", "Cyclone Mobile", "Install + launch"
    ]
    assert CURRENT_ONE_VERSION == "1.1.2"
    assert CURRENT_MOBILE_BASELINE == "4.3.6"
    assert VMOS_REMOTE_ADB_SESSION_HOURS == 24
    assert "minSdk is 33" in plan[0]["reason"]
    assert "bundles CyclonePCRuntime/PC Agent" in plan[1]["reason"]
    assert "adb -s <serial> install -r" in plan[4]["action"]
    assert "com.cyclone.mobile/.MainActivity" in plan[4]["action"]


def test_prerequisites_accept_preferred_android15_path():
    result = validate_prerequisites(_ready())

    assert result.ready is True
    assert result.blockers == ()


def test_android13_and_14_are_supported_compatibility_targets():
    for major in (13, 14):
        result = validate_prerequisites(_ready(vmos_android_major=major))
        assert result.ready is True
        assert result.warnings == (f"Android 15 is the preferred VMOS target; Android {major} is compatibility mode.",)


def test_android10_is_rejected_by_cyclone_min_sdk():
    result = validate_prerequisites(_ready(vmos_android_major=10))

    assert result.ready is False
    assert "minSdk 33" in result.blockers[0]


def test_remote_adb_authorization_and_live_session_are_real_blockers():
    unauthorized = validate_prerequisites(_ready(vmos_adb_account_authorized=False))
    closed = validate_prerequisites(_ready(vmos_adb_session_open=False))

    assert unauthorized.ready is False
    assert any("remote ADB permission" in item for item in unauthorized.blockers)
    assert closed.ready is False
    assert any("Local Debugging" in item for item in closed.blockers)


def test_old_edge_image_is_only_rejected_when_edge_control_api_is_requested():
    old_image = "vcloud_android15_edge_20251227201917"

    baseline = validate_prerequisites(_ready(vmos_image=old_image, needs_edge_control_api=False))
    edge_api = validate_prerequisites(_ready(vmos_image=old_image, needs_edge_control_api=True))

    assert baseline.ready is True
    assert edge_api.ready is False
    assert any(EDGE_ANDROID15_CONTROL_API_MIN_IMAGE in item for item in edge_api.blockers)


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
