import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("kba", Path(__file__).with_name("inspect-kba-freight.py"))
kba = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kba)


class KbaImportTest(unittest.TestCase):
    def package(self, folder, rows, name="PUF-VD_1a-2025.csv"):
        archive = Path(folder) / "input.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr(name, "JAHR;CODE;FT_I;FT_I_ZS\n" + rows)
        return archive

    def test_decimal_and_quality_preserved_without_summing_dimensions(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = self.package(folder,"2025;A;12,5;\n2025;B;2,0;()\n2025;C;;/\n2025;D;;-\n")
            output = Path(folder)/"out"
            summary = kba.inspect_package(archive, output, "https://example.test/source", "2026-10-12")
            records = [json.loads(line) for line in (output/"observations.jsonl").read_text().splitlines()]
            self.assertEqual(["12.5","2.0",None,None],[r["value"] for r in records])
            self.assertEqual("suppressed",records[2]["quality"])
            self.assertEqual({"JAHR":"2025","CODE":"A"},records[0]["dimensions"])
            self.assertFalse(summary["dailyDemandGenerated"])
            self.assertFalse(summary["codesDecoded"])
            self.assertEqual(64,len(summary["zipSha256"]))

    def test_invalid_package_leaves_no_partial_output(self):
        for rows in ("2024;A;1;\n", "2025;A;nan;\n", "2025;A;1;/\n", "2025;A;1;unknown\n"):
            with tempfile.TemporaryDirectory() as folder:
                archive = self.package(folder,rows)
                output = Path(folder)/"out"
                with self.assertRaises(ValueError):
                    kba.inspect_package(archive,output,"https://example.test/source","2026-10-12")
                self.assertFalse(output.exists())

    def test_archive_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = self.package(folder,"", "../PUF-VD_1a-2025.csv")
            with self.assertRaises(ValueError):
                kba.inspect_package(archive,Path(folder)/"out","https://example.test/source","2026-10-12")


if __name__ == "__main__":
    unittest.main()
