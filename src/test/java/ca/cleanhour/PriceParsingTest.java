package ca.cleanhour;

import ca.cleanhour.ieso.IesoClient;
import ca.cleanhour.ieso.PriceDay;
import ca.cleanhour.ieso.RealtimePrice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Samples follow the structure of IESO's Ontario Zonal Price reports (namespace http://www.ieso.ca/schema). */
class PriceParsingTest {

    private static Object call(String method, String xml) throws Exception {
        IesoClient c = new IesoClient(new CleanHourProperties());
        Method m = IesoClient.class.getDeclaredMethod(method, java.io.InputStream.class);
        m.setAccessible(true);
        return m.invoke(c, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesDayAheadHourlyZonalPrice() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" href="x.xsl"?>
                <Document xmlns="http://www.ieso.ca/schema" docID="DAHourlyOntarioZonalPrice">
                <DocHeader><DocTitle>Day Ahead Hourly Ontario Zonal Energy Price Report</DocTitle><DocRevision>1</DocRevision>
                <CreatedAt>2026-11-14T13:31:14</CreatedAt></DocHeader>
                <DocBody><DeliveryDate>2026-11-15</DeliveryDate>
                <HourlyPriceComponents><PricingHour>1</PricingHour><ZonalPrice>49.39</ZonalPrice><LossPriceCapped>0.08</LossPriceCapped><CongestionPriceCapped>2.15</CongestionPriceCapped><Flag>DSO-RD</Flag></HourlyPriceComponents>
                <HourlyPriceComponents><PricingHour>18</PricingHour><ZonalPrice>108.15</ZonalPrice><LossPriceCapped>-0.28</LossPriceCapped><CongestionPriceCapped>0.08</CongestionPriceCapped><Flag>DSO-RD</Flag></HourlyPriceComponents>
                </DocBody></Document>
                """.strip();
        PriceDay d = (PriceDay) call("parseHourlyPrice", xml);
        assertEquals(LocalDate.of(2026, 11, 15), d.date());
        assertEquals(49.39, d.hours().get(1));
        assertEquals(108.15, d.hours().get(18));
        assertEquals(2, d.hours().size());
    }

    @Test
    void parsesPartialRealtimeHour() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="http://www.ieso.ca/schema" docID="RealtimeOntarioZonalPrice">
                <DocHeader><DocTitle>Real-Time 5-min Ontario Zonal Energy Price Report</DocTitle></DocHeader>
                <DocBody><DeliveryDate>2026-11-14</DeliveryDate><DeliveryHour>17</DeliveryHour>
                <ZonalPrice><Interval>1</Interval><Flag>DSO-RD</Flag><LmpCap>51.13</LmpCap><LossPriceCap>-0.07</LossPriceCap><CongPriceCap>0.05</CongPriceCap></ZonalPrice>
                <ZonalPrice><Interval>2</Interval><Flag>DSO-RD</Flag><LmpCap>58.30</LmpCap><LossPriceCap>-0.08</LossPriceCap><CongPriceCap>5.63</CongPriceCap></ZonalPrice>
                <ZonalPrice><Interval>3</Interval><Flag/><LmpCap/><LossPriceCap/><CongPriceCap/></ZonalPrice>
                <AveragePrice><LmpCap>54.72</LmpCap><LossPriceCap>-0.08</LossPriceCap><CongPriceCap>2.84</CongPriceCap></AveragePrice>
                </DocBody></Document>
                """.strip();
        RealtimePrice p = (RealtimePrice) call("parseRealtimePrice", xml);
        assertEquals(LocalDate.of(2026, 11, 14), p.date());
        assertEquals(17, p.hourEnding());
        assertEquals(2, p.intervals());
        assertEquals(54.72, p.average());
    }
}
