package blockchain;

import java.util.List;
import java.util.Map;
import model.LedgerTransaction;

@FunctionalInterface
public interface PendingTransactionSource {
    Selection selectForNextBlock();

    record Selection(List<LedgerTransaction> selected, Map<String, String> deferred) {
        public Selection {
            selected = List.copyOf(selected);
            deferred = Map.copyOf(deferred);
        }
    }
}
