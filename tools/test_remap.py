"""Regression checks for APK remapping (run with python -m unittest discover -s tools)."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
import zipfile

from remap import extract_base_apk, find_clear_method, find_controller, parse_params, verify_runtime_layout


def method(name, descriptor, instructions=()):
    value = Mock()
    value.get_name.return_value = name
    value.get_descriptor.return_value = descriptor
    value.get_code.return_value.get_bc.return_value.get_instructions.return_value = instructions
    return value


def controller(name, arrow_descriptor):
    value = Mock()
    value.get_name.return_value = name
    value.get_methods.return_value = [
        method("J", "()Lox5;"), method("e0", arrow_descriptor),
    ]
    return value


ARROW = ("(Lh55; Lh55; Z Lcom/duolingo/chess/model/rive/ArrowType; "
         "I Lcom/duolingo/chess/model/rive/ArrowColor;)V")


class RemapTests(unittest.TestCase):
    def test_androguard_spaced_signature(self):
        self.assertEqual(parse_params(ARROW), [
            "Lh55;", "Lh55;", "Z", "Lcom/duolingo/chess/model/rive/ArrowType;",
            "I", "Lcom/duolingo/chess/model/rive/ArrowColor;",
        ])

    def test_arrays_and_no_arguments(self):
        self.assertEqual(parse_params("([[I [Ljava/lang/String; Z)V"),
                         ["[[I", "[Ljava/lang/String;", "Z"])
        self.assertEqual(parse_params("()Lox5;"), [])

    def test_controller_requires_exact_arrow_types(self):
        dex = Mock()
        dex.get_classes.return_value = [
            controller("La;", "(Lh55; Lh55; Z Ljava/lang/String; I Ljava/lang/String;)V"),
            controller("Lmz6;", ARROW),
        ]
        self.assertEqual(find_controller({"classes.dex": dex}, "ox5"), ("Lmz6;", "Lh55;"))

    def test_ambiguous_controller_is_rejected(self):
        dex = Mock()
        dex.get_classes.return_value = [controller("La;", ARROW), controller("Lb;", ARROW)]
        with self.assertRaisesRegex(ValueError, "ambiguous board controllers"):
            find_controller({"classes.dex": dex}, "ox5")

    def test_clear_method_uses_reset_arrows_event(self):
        reset = Mock()
        reset.get_name.return_value = "sget-object"
        reset.get_output.return_value = "v0, Laaz;->a Laaz;"
        other = Mock()
        other.get_name.return_value = "sget-object"
        other.get_output.return_value = "v0, Lcaz;->a Lcaz;"
        value = Mock()
        value.get_methods.return_value = [method("d0", "()V", [other]),
                                         method("c0", "()V", [reset])]
        self.assertEqual(find_clear_method(value, "Laaz;"), "c0")

    def test_missing_clear_method_is_rejected(self):
        value = Mock()
        value.get_methods.return_value = []
        with self.assertRaisesRegex(ValueError, "clearArrows"):
            find_clear_method(value, "Laaz;")

    def test_changed_runtime_field_layout_is_rejected(self):
        state = Mock()
        state.get_name.return_value = "Lox5;"
        field = Mock()
        field.get_name.return_value = "a"
        field.get_descriptor.return_value = "Ljava/lang/String;"
        state.get_fields.return_value = [field]
        state.get_methods.return_value = []
        dex = Mock()
        dex.get_classes.return_value = [state]
        with self.assertRaisesRegex(ValueError, "chessGameState layout changed"):
            verify_runtime_layout({"classes.dex": dex}, {"chessGameState": "Lox5;", "chessFen": "Ldm5;"})

    def test_bundle_selects_base_instead_of_base_named_split(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "app.apks"
            with zipfile.ZipFile(path, "w") as bundle:
                bundle.writestr("split_config.base.apk", b"wrong split")
                bundle.writestr("base.apk", b"base apk")
            self.assertEqual(extract_base_apk(path), b"base apk")

    def test_bundle_without_identifiable_base_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "app.apks"
            with zipfile.ZipFile(path, "w") as bundle:
                bundle.writestr("one.apk", b"one")
                bundle.writestr("two.apk", b"two")
            with self.assertRaisesRegex(ValueError, "base APK"):
                extract_base_apk(path)


if __name__ == "__main__":
    unittest.main()
