# -*- coding: utf-8 -*-
"""Ajuste le solde final du compte courant à un montant réaliste non rond (1516,89).

Méthode : on réduit le dernier virement de plafonnement ("TX-VIR-EP-2026-08-cap")
de 16,89 EUR -> le compte garde 16,89 de plus => solde final 1516,89 au lieu de 1500,00.
Propagation cohérente : épargne (Livret A), synthese_financier.json, transactions mois 08/2026.
"""
import json
import re

BANK = "data/banking_demo_normalized.json"
EPARGNE = "data/compte_epargne.json"
SYNTH = "data/synthese_financier.json"
TX08 = "data/transaction/transactions_2026_08.json"

TARGET = 1516.89
DELTA = round(TARGET - 1500.0, 2)  # 16.89


def r2(x):
    return round(x, 2)


def update_cap_op(ops, delta):
    """Réduit de `delta` l'opération cap de la liste et met à jour son libellé."""
    for op in ops:
        tid = op.get("transactionId")
        if tid == "TX-VIR-EP-2026-08-cap" or (
            tid is None and "VIREMENT VERS COMPTE EPARGNE" in (op.get("nature_operation") or "").upper()
            and op.get("debit") is not None and abs(op["debit"] - 544.82) < 0.01
        ):
            new_amount = r2(op["debit"] - delta)
            op["debit"] = new_amount
            op["nature_operation"] = re.sub(
                r"\d+(\.\d+)? EUR", f"{new_amount:.2f} EUR", op["nature_operation"])
            return True
    return False


# ---- 1) Maître ----
bank = json.load(open(BANK, encoding="utf-8"))
months = bank["accounts"][0]["transactions"]["months"]
last = months[-1]
assert last["periode"] == "08/2026", last["periode"]
assert update_cap_op(last["operations"], DELTA), "cap op introuvable dans le maître"

for k in list(last.keys()):
    if k.startswith("nouveau_solde"):
        last[k] = TARGET

tot_debit = r2(sum((o.get("debit") or 0) for o in last["operations"]))
tot_credit = r2(sum((o.get("credit") or 0) for o in last["operations"]))
last["totaux_des_mouvements"]["debit"] = tot_debit
last["totaux_des_mouvements"]["credit"] = tot_credit
last["controle"]["total_debit_calcule"] = tot_debit
last["controle"]["total_credit_calcule"] = tot_credit
last["controle"]["correspond_au_releve"] = True

# Vérification finale du dernier mois
openv = [v for k, v in last.items() if k.startswith("solde_precedent")][0]
closev = [v for k, v in last.items() if k.startswith("nouveau_solde")][0]
assert abs((openv + tot_credit - tot_debit) - closev) < 0.01, "déséquilibre dernier mois"

# Vérification de cohérence sur TOUS les mois (chaîne de soldes)
for mm in months:
    o = [v for k, v in mm.items() if k.startswith("solde_precedent")][0]
    c = [v for k, v in mm.items() if k.startswith("nouveau_solde")][0]
    td = mm["totaux_des_mouvements"]["debit"]
    tc = mm["totaux_des_mouvements"]["credit"]
    assert abs((o + tc - td) - c) < 0.01, f"incohérent {mm['periode']}"

# Épargne (Livret A) : elle reçoit 16,89 de moins
for a in bank["savingsAccounts"]:
    if a.get("accountId") == "LIVRET-A-001":
        a["solde"] = r2(a["solde"] - DELTA)

json.dump(bank, open(BANK, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

# ---- 2) compte_epargne.json ----
ep = json.load(open(EPARGNE, encoding="utf-8"))
for a in ep["savingsAccounts"]:
    if a.get("accountId") == "LIVRET-A-001":
        a["solde"] = r2(a["solde"] - DELTA)
json.dump(ep, open(EPARGNE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

savings_total = r2(sum((a.get("solde") or 0) for a in ep["savingsAccounts"]))

# ---- 3) synthese_financier.json ----
syn = json.load(open(SYNTH, encoding="utf-8"))
syn["accounts"][0]["balance"] = TARGET
for ms in syn["monthlySummaries"]:
    if ms.get("month") == "08/2026":
        ms["balance"] = TARGET
syn["savingsTotal"] = savings_total
syn["global"]["currentAccountBalance"] = TARGET
syn["global"]["savingsTotal"] = savings_total
json.dump(syn, open(SYNTH, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

# ---- 4) transactions_2026_08.json ----
tx = json.load(open(TX08, encoding="utf-8"))
assert update_cap_op(tx["transactions"], DELTA), "cap op introuvable dans transactions_2026_08"
json.dump(tx, open(TX08, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

print("OK")
print(f"solde final courant : {closev} -> {TARGET}")
print(f"virement cap : {r2(544.82 - DELTA):.2f} EUR")
print(f"Livret A : {r2(12212.07 - DELTA):.2f} | savingsTotal : {savings_total}")
print(f"totaux 08/2026 : debit={tot_debit} credit={tot_credit}")
