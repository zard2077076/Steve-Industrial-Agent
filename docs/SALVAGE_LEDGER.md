# Salvage ledger

`SalvageLedger` records the exact authorized destination and one immutable entry
per cleared obstacle: resource identity, expected quantity, actual collected
quantity, delivered quantity and executor identity. Delivered quantity cannot
exceed collected quantity. The ledger never reads player inventory or private
container contents.
