package ca.cleanhour;

import ca.cleanhour.carbon.CarbonCalculator;
import ca.cleanhour.ieso.DayReport;
import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.ieso.IesoClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IesoParsingTest {

    private static CleanHourProperties props() {
        CleanHourProperties p = new CleanHourProperties();
        Map<Fuel, Double> f = new EnumMap<>(Fuel.class);
        f.put(Fuel.NUCLEAR, 12.0);
        f.put(Fuel.GAS, 490.0);
        f.put(Fuel.HYDRO, 24.0);
        p.setEmissionFactors(f);
        return p;
    }

    private static DayReport call(IesoClient c, String method, String xml) throws Exception {
        Method m = IesoClient.class.getDeclaredMethod(method, java.io.InputStream.class);
        m.setAccessible(true);
        return (DayReport) m.invoke(c, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesOutputReportAndIgnoresCapability() throws Exception {
        String xml = """
                <IMODocument xmlns="http://www.theIMO.com/schema"><IMODocBody><Date>2026-11-11</Date><Generators>
                <Generator><GeneratorName>A</GeneratorName><FuelType>NUCLEAR</FuelType>
                  <Outputs><Output><Hour>1</Hour><EnergyMW>800</EnergyMW></Output></Outputs>
                  <Capabilities><Capability><Hour>1</Hour><EnergyMW>9999</EnergyMW></Capability></Capabilities></Generator>
                <Generator><GeneratorName>B</GeneratorName><FuelType>GAS</FuelType>
                  <Outputs><Output><Hour>1</Hour><EnergyMW>200</EnergyMW></Output><Output><Hour>2</Hour><EnergyMW></EnergyMW></Output></Outputs></Generator>
                </Generators></IMODocBody></IMODocument>""";
        DayReport r = call(new IesoClient(props()), "parseOutput", xml);
        assertEquals(LocalDate.of(2026, 11, 11), r.date());
        assertEquals(800.0, r.hours().get(1).get(Fuel.NUCLEAR));
        assertEquals(200.0, r.hours().get(1).get(Fuel.GAS));
        assertNull(r.hours().get(2));
    }

    @Test
    void parsesAdequacySchedules() throws Exception {
        String xml = """
                <Document xmlns="http://www.ieso.ca/schema"><DocBody><DeliveryDate>2026-11-12</DeliveryDate>
                <ForecastSupply><InternalResources><InternalResource><FuelType>Gas</FuelType>
                  <Capacities><Capacity><DeliveryHour>1</DeliveryHour><EnergyMW>11000</EnergyMW></Capacity></Capacities>
                  <Schedules><Schedule><DeliveryHour>1</DeliveryHour><EnergyMW>4212</EnergyMW></Schedule></Schedules>
                </InternalResource></InternalResources></ForecastSupply></DocBody></Document>""";
        DayReport r = call(new IesoClient(props()), "parseSchedule", xml);
        assertEquals(LocalDate.of(2026, 11, 12), r.date());
        assertEquals(4212.0, r.hours().get(1).get(Fuel.GAS));
    }

    @Test
    void intensityIsOutputWeightedAndSkipsUncountedFuels() {
        CarbonCalculator calc = new CarbonCalculator(props());
        Map<Fuel, Double> mix = new EnumMap<>(Fuel.class);
        mix.put(Fuel.NUCLEAR, 800.0);
        mix.put(Fuel.GAS, 200.0);
        mix.put(Fuel.OTHER, 500.0);
        assertEquals((800 * 12 + 200 * 490) / 1000.0, calc.intensity(mix), 1e-9);
    }
}
