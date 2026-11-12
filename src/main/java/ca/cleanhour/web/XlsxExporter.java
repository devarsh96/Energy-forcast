package ca.cleanhour.web;

import ca.cleanhour.carbon.ReportService.HourlyRecord;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Writes the hourly table to an Excel workbook, streaming straight to the response. */
final class XlsxExporter {

    private XlsxExporter() { }

    static void write(List<HourlyRecord> rows, List<String> fuels, OutputStream out, String note) throws IOException {
        Workbook wb = new Workbook(out, "CleanHour", "1.0");
        Worksheet ws = wb.newWorksheet("Hourly data");

        List<String> headers = new ArrayList<>(List.of("Local time (Toronto)", "Date", "Hour"));
        for (String f : fuels) headers.add(f.charAt(0) + f.substring(1).toLowerCase() + " (MW)");
        headers.addAll(List.of("Total output (MW)", "Ontario demand (MW)", "Intensity (g CO2e/kWh)",
                "Day-ahead forecast intensity (g CO2e/kWh)", "Emissions (t CO2e)"));
        for (int c = 0; c < headers.size(); c++) {
            ws.value(0, c, headers.get(c));
            ws.style(0, c).bold().fillColor("E1E0D9").set();
            ws.width(c, c == 0 ? 20 : Math.max(12, Math.min(28, headers.get(c).length() + 2)));
        }

        int r = 1;
        for (HourlyRecord h : rows) {
            int c = 0;
            ws.value(r, c++, h.localTime().replace('T', ' '));
            ws.value(r, c++, h.date().toString());
            ws.value(r, c++, h.hour());
            for (String f : fuels) num(ws, r, c++, h.fuelMw().get(f));
            num(ws, r, c++, h.totalMw());
            num(ws, r, c++, h.demandMw());
            num(ws, r, c++, h.intensity());
            num(ws, r, c++, h.dayAheadIntensity());
            num(ws, r, c++, h.tonnesCo2());
            r++;
        }

        Worksheet about = wb.newWorksheet("About");
        about.value(0, 0, "Clean Hour Ontario - hourly IESO data export");
        about.style(0, 0).bold().set();
        about.value(2, 0, note);
        about.value(3, 0, "Source: IESO public reports (GenOutputCapability, GenOutputbyFuelHourly, Adequacy3, Demand).");
        about.value(4, 0, "Emission factors: IPCC AR5 WGIII Annex III lifecycle medians. Storage/other output is not counted in intensity.");
        about.width(0, 110);
        wb.finish();
    }

    private static void num(Worksheet ws, int r, int c, Double v) {
        if (v != null) ws.value(r, c, v);
    }
}
