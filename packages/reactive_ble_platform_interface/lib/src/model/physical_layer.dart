import '../model/unit.dart';
import 'generic_failure.dart';
import 'result.dart';

/// The priority that can be requested to update the connection parameter.
enum Phy {
  /// Default 1M physical layer.
  le1M,

  /// Double throughpu 2M physical layer.
  le2M,

  /// Coded (low-speed) physical layer
  coded,
}

///util function to convert priority to a integer.
int convertPhyToInt(Phy phy) {
  switch (phy) {
    case Phy.le1M:
      return 1;
    case Phy.le2M:
      return 2;
    case Phy.coded:
      return 3;
    default:
      assert(false);
      return -1000;
  }
}

class PhyPair {
  final Phy tx;
  final Phy rx;

  const PhyPair({required this.tx, required this.rx});

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PhyPair && tx == other.tx && rx == other.rx;

  @override
  int get hashCode => tx.hashCode ^ rx.hashCode;
}

/// Result of the connection priority request
class SetPreferredPhyInfo {
  const SetPreferredPhyInfo({required this.result});

  final Result<PhyPair, GenericFailure<SetPreferredPhyFailure>?> result;
}

/// Error type for connection priority.
enum SetPreferredPhyFailure { unknown }
