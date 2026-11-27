import unittest
from unittest.mock import Mock, patch
from uasm_qt.wifi import WifiBoard, assignment, pair_socket, PairingRejected, verified_board
from uasm_qt.sender import parse_wifi_endpoint


class WifiSenderTests(unittest.TestCase):
    def board(self, host="", name=""):
        return WifiBoard("192.168.0.160", 5000, "Desk", host, name, "192.168.0.160")

    def test_assignment_all_restrictions(self):
        self.assertEqual(assignment(self.board(), "192.168.0.22", "desk"), "Unassigned")
        self.assertEqual(assignment(self.board("192.168.0.22", "DESK"), "192.168.0.22", "desk"), "This PC")
        self.assertEqual(assignment(self.board("192.168.0.22", "other"), "192.168.0.22", "desk"), "Other PC")
        self.assertEqual(assignment(self.board("192.168.0.21"), "192.168.0.22", "desk"), "Other PC")

    def test_fragmented_handshake(self):
        sock = Mock()
        sock.getsockname.return_value = ("192.168.0.22", 40000)
        sock.recv.side_effect = [b"PAIR_", b"OK\r\n"]
        self.assertEqual(pair_socket(sock, "desk"), "192.168.0.22")
        sock.sendall.assert_called_once_with(b"UAM_PAIR|desk|192.168.0.22\n")

    def test_rejection(self):
        sock = Mock()
        sock.getsockname.return_value = ("192.168.0.22", 40000)
        sock.recv.return_value = b"PAIR_REJECT|wrong_host\n"
        with self.assertRaises(PairingRejected):
            pair_socket(sock, "desk")

    def test_no_claim_unassigned(self):
        with patch("uasm_qt.wifi.discover", return_value=[self.board()]):
            with self.assertRaises(PairingRejected):
                verified_board("192.168.0.160", 5000)

    def test_missing_board(self):
        with patch("uasm_qt.wifi.discover", return_value=[]):
            with self.assertRaises(ConnectionError):
                verified_board("192.168.0.160", 5000)

    def test_endpoint(self):
        self.assertEqual(parse_wifi_endpoint("192.168.0.160:5000"), ("192.168.0.160", 5000))
