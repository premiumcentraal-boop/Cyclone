from cyclone_device_gateway.vmos.prerequisites import (
    CURRENT_MOBILE_BASELINE,
    CURRENT_ONE_VERSION,
    EDGE_ANDROID15_CONTROL_API_MIN_IMAGE,
    INSTALL_ORDER,
    PrerequisiteSnapshot,
    installation_plan,
    validate_prerequisites,
)


def test_install_order_is_single_clear_path():
    plan = installation_plan()

    assert [item["order"] for item in plan] == [1, 2, 3, 4, 5]
    assert [item["component"] for item in plan] == ["VMOS", "Cyclone One", "ADB", "Cyclone Mobile", "VMOS + Mobile"]
    assert CURRENT_ONE_VERSION == "1.1.2"
    assert CURRENT_MOBILE_BASELINE == "4.3.6"
    assert "bundles" in plan[1]["reason"]


def test_prerequisites_accept_preferred_android15_path():
    result = validate_prerequisites(PrerequisiteSnapshot(
        windows_major=10,
        cyclone_one_version="1.1.2",
        adb_available=True,
        mobile_apk=r"C:\\Cyclone\\Cyclone-4.3.6.apk",
        vmos_android_major=15,
        vmos_image="cloud-dynamic-image-id",
    ))

    assert result.ready is True
    assert result.blockers == ()


def test_android13_is_compatibility_mode_not_false_failure():
    result = validate_prerequisites(PrerequisiteSnapshot(
        windows_major=11,
        cyclone_one_version="1.1.2",
        adb_available=True,
        mobile_apk="Cyclone-4.3.6.apk",
        vmos_android_major=13,
    ))

    assert result.ready is True
    assert result.warnings == ("Android 15 is the preferred VMOS target; Android 13 is compatibility mode.",)


def test_old_edge_android15_image_is_rejected():
    result = validate_prerequisites(PrerequisiteSnapshot(
        windows_major=11,
        cyclone_one_version="1.1.2",
        adb_available=True,
        mobile_apk="Cyclone-4.3.6.apk",
        vmos_android_major=15,
        vmos_image="vcloud_android15_edge_20251227201917",
    ))

    assert result.ready is False
    assert EDGE_ANDROID15_CONTROL_API_MIN_IMAGE in result.blockers[0]


def test_missing_pc_runtime_is_expressed_as_one_install_not_two():
    result = validate_prerequisites(PrerequisiteSnapshot(
        windows_major=11,
        cyclone_one_version=None,
        adb_available=False,
        mobile_apk=None,
        vmos_android_major=None,
    ))

    assert result.ready is False
    assert any("Install Cyclone One 1.1.2" in item for item in result.blockers)
    assert not any("install PC Agent" in item.lower() for item in result.blockers)
