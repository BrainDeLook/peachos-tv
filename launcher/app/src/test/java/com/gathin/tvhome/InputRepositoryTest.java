package com.gathin.tvhome;

import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputRepositoryTest {
    private InputRepository.Input input(String id, String parent, String label, String custom, int type, int state) {
        return new InputRepository.Input(id, parent, label, custom, type != TvInputInfo.TYPE_TUNER, true, type, state);
    }
    @Test public void cecDeviceReplacesPortWithTheSameTclConnectionCorrectionOnBothHosts() {
        List<InputRepository.Entry> rows = InputRepository.arrange(List.of(
                input("tv/HW1", null, "HDMI 1", "", TvInputInfo.TYPE_HDMI, TvInputManager.INPUT_STATE_CONNECTED),
                input("tv/CEC1", "tv/HW1", "Apple TV", "", TvInputInfo.TYPE_HDMI, TvInputManager.INPUT_STATE_CONNECTED_STANDBY)), "TCL", Set.of());
        assertEquals(1, rows.size()); assertEquals("tv/CEC1", rows.get(0).id);
        assertEquals("Apple TV · HDMI 1", rows.get(0).label); assertEquals(TvInputManager.INPUT_STATE_CONNECTED, rows.get(0).state);
    }
    @Test public void bogusAllConnectedStateAndOmittedPortsRemainSharedPolicy() {
        List<InputRepository.Input> inputs = List.of(input("tv/HW1", null, "1", "Console", TvInputInfo.TYPE_HDMI, 0),
                input("tv/HW2", null, "HDMI 2", "", TvInputInfo.TYPE_HDMI, 0));
        List<InputRepository.Entry> rows = InputRepository.arrange(inputs, "Sony", Set.of());
        assertEquals(2, rows.size()); assertEquals(-1, rows.get(0).state); assertEquals("Console · HDMI 1", rows.get(0).label);
        assertEquals(1, InputRepository.arrange(inputs, "Sony", Set.of("tv/HW2")).size());
    }
    @Test public void hardwareDigitalTunerWinsOverAnalogAndSoftwareInputs() {
        List<InputRepository.Input> inputs = List.of(input("tv/Software", null, "AirPlay", "", TvInputInfo.TYPE_TUNER, -1),
                input("tv/Analog/HW1", null, "Analog", "", TvInputInfo.TYPE_TUNER, -1),
                input("tv/Digital/HW2", null, "Digital", "", TvInputInfo.TYPE_TUNER, -1));
        assertEquals("tv/Digital/HW2", InputRepository.preferredTuner(inputs, ""));
        assertEquals("tv/Analog/HW1", InputRepository.preferredTuner(inputs, "tv/Analog/HW1"));
        assertNull(InputRepository.preferredTuner(inputs, "tv/missing"));
    }
    @Test public void ordinaryBoxHasNoEmptySourceTab() {
        assertTrue(InputRepository.arrange(List.of(), "Generic", Set.of()).isEmpty());
        assertFalse(new InputRepository.Snapshot(List.of(), true).available());
    }
}
