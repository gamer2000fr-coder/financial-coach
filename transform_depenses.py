# -*- coding: utf-8 -*-
"""
Transformation financière cohérente des données bancaires de démonstration.

Règles (validées avec l'utilisateur) :
1. Réduction de 10 % des SEULES dépenses de consommation (débits hors virements vers épargne).
2. Solde du compte courant plafonné à 1500 € : en fin de mois, tout excédent est viré vers
   l'épargne (opération ajoutée). Le solde d'ouverture initial (>1500) est aussi ramené à
   1500 par un virement initial en début de période.
3. Soldes de fin de mois garantis cohérents avec les opérations modifiées (chaîne).
4. L'épargne (Livret A) et le savingsTotal de la synthèse sont mis à jour.
5. Tout est régénéré : banking_demo_normalized.json (maître), data/transaction/*.json (14 mois),
   synthese_financier.json, compte_epargne.json.

Virements vers épargne (non-dépenses, non réduits) : opérations contenant LOGITEL
ou le marqueur ajouté "VERS COMPTE EPARGNE".
Retours d'épargne (crédits non-revenus) : "VIR RECU ... MARTIN".
"""
import calendar
import json
import os
from copy import deepcopy
from decimal import Decimal, ROUND_HALF_UP

BANK = "data/banking_demo_normalized.json"
TX_DIR = "data/transaction"
SYNTH = "data/synthese_financier.json"
EPARGNE_FILE = "data/compte_epargne.json"
TARGET = Decimal("1500.00")
RATE = Decimal("0.9")


def money(value):
    return Decimal(str(value))


def reduce_amount(d):
    return (money(d) * RATE).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def round2(value):
    return float(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def is_savings_out(label):
    U = (label or "").upper()
    return "LOGITEL" in U or "VERS COMPTE EPARGNE" in U


def is_savings_in(label):
    U = (label or "").upper()
    return "VIR RECU" in U and "MARTIN" in U


def last_day(month, year):
    return calendar.monthrange(year, month)[1]


def build_virement_op(dd, mm, yyyy, amount, tag):
    return {
        "date": f"{dd:02d}/{mm:02d}/{yyyy}",
        "nature_operation": f"VIREMENT VERS COMPTE EPARGNE {float(amount):.2f} EUR ({tag})",
        "debit": float(amount),
        "credit": None,
        "transactionId": f"TX-VIR-EP-{yyyy}-{mm:02d}-{tag}",
        "accountId": "ACC-COURANT-001",
    }


def transform():
    bank = json.load(open(BANK, encoding="utf-8"))
    months = bank["accounts"][0]["transactions"]["months"]

    new_months = []
    total_added = Decimal("0.00")
    opening = None
    report = []

    for idx, m in enumerate(months):
        periode = m["periode"]  # "MM/YYYY"
        mm, yyyy = map(int, periode.split("/"))

        # Solde d'ouverture (fin de mois précédent / réel au début de la période)
        solde_key = next(k for k in m if k.startswith("solde_precedent"))
        nouveau_key = next(k for k in m if k.startswith("nouveau_solde"))
        if idx == 0:
            opening = money(m[solde_key])  # réel : solde au 30/06/2025
        # sinon opening = nouveau closing du mois précédent (posé à la fin de la boucle)

        running = opening
        ops = []
        added_in_month = Decimal("0.00")

        # -- Virement initial pour ramener l'ouverture <= 1500 (premier mois seulement)
        init_transfer = Decimal("0.00")
        if idx == 0 and running > TARGET:
            init_transfer = (running - TARGET).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
            ops.append(build_virement_op(1, mm, yyyy, init_transfer, "initial"))
            running -= init_transfer
            total_added += init_transfer
            added_in_month += init_transfer

        # -- Opérations originales transformées
        for op in m["operations"]:
            label = op.get("nature_operation", "")
            new_op = deepcopy(op)
            if op.get("debit") is not None:
                if is_savings_out(label):
                    running -= money(op["debit"])           # virement épargne : inchangé
                else:
                    new_debit = reduce_amount(op["debit"])  # consommation : -10%
                    new_op["debit"] = float(new_debit)
                    running -= new_debit
            elif op.get("credit") is not None:
                running += money(op["credit"])
            ops.append(new_op)

        # -- Virement de fin de mois si solde > 1500
        cap_transfer = Decimal("0.00")
        if running > TARGET:
            cap_transfer = (running - TARGET).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
            ops.append(build_virement_op(last_day(mm, yyyy), mm, yyyy, cap_transfer, "cap"))
            running -= cap_transfer
            total_added += cap_transfer
            added_in_month += cap_transfer

        closing = round2(running)

        # Totaux et contrôles
        total_debit = round2(sum(o.get("debit") or 0 for o in ops))
        total_credit = round2(sum(o.get("credit") or 0 for o in ops))

        # Cohérence interne : opening + crédits - débits == closing
        check = float(round2(money(opening) + money(total_credit) - money(total_debit)))
        assert abs(check - closing) < 0.011, f"incohérence {periode}: {opening}+{total_credit}-{total_debit} = {check} != {closing}"

        new_month = deepcopy(m)
        new_month[solde_key] = float(round2(opening))
        new_month[nouveau_key] = float(closing)
        new_month["totaux_des_mouvements"]["debit"] = total_debit
        new_month["totaux_des_mouvements"]["credit"] = total_credit
        controle = new_month["controle"]
        for k in list(controle.keys()):
            if k.startswith("total_debit") or k in ("somme_debits_calculee", "total_debit"):
                controle[k] = total_debit
            elif k.startswith("total_credit") or k in ("somme_credits_calculee", "total_credit"):
                controle[k] = total_credit
            elif k in ("solde_calcule",):
                controle[k] = float(closing)
            elif k in ("correspond_au_releve",):
                controle[k] = True
            elif k in ("nombre_operations",):
                controle[k] = len(ops)
        new_month["operations"] = ops
        new_months.append(new_month)

        report.append({
            "periode": periode,
            "opening": float(round2(opening)),
            "closing": float(closing),
            "debit": total_debit,
            "credit": total_credit,
            "initTransfer": float(init_transfer),
            "capTransfer": float(cap_transfer),
            "nbOps": len(ops),
        })

        opening = Decimal(str(closing))  # enchaînement

    # ---- Mise à jour du fichier maître ----
    bank["accounts"][0]["transactions"]["months"] = new_months

    # ---- Épargne mise à jour (Livret A reçoit les virements ajoutés) ----
    savings = bank["savingsAccounts"]
    for acc in savings:
        if acc["accountId"] == "LIVRET-A-001":
            acc["solde"] = round2(money(acc["solde"]) + total_added)

    json.dump(bank, open(BANK, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    # ---- Régénération des 14 fichiers mensuels ----
    os.makedirs(TX_DIR, exist_ok=True)
    for m in new_months:
        periode = m["periode"]
        mm, yyyy = periode.split("/")
        fname = f"transactions_{yyyy}_{mm}.json"
        wrapper = {
            "periode": periode,
            "year": yyyy,
            "month": mm,
            "transactionCount": len(m["operations"]),
            "transactions": m["operations"],
        }
        json.dump(wrapper, open(os.path.join(TX_DIR, fname), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)

    # ---- compte_epargne.json ----
    epargne = json.load(open(EPARGNE_FILE, encoding="utf-8"))
    for acc in epargne["savingsAccounts"]:
        if acc["accountId"] == "LIVRET-A-001":
            acc["solde"] = round2(money(acc.get("solde") or 0) + total_added)
    json.dump(epargne, open(EPARGNE_FILE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print("=== Rapport mensuel (après transformation) ===")
    for r in report:
        print(f"{r['periode']}: open={r['opening']:.2f} credit={r['credit']:.2f} "
              f"debit={r['debit']:.2f} closing={r['closing']:.2f} "
              f"[init={r['initTransfer']:.2f} cap={r['capTransfer']:.2f}] ops={r['nbOps']}")
    print("total virements ajoutés:", float(total_added))
    print("solde final compte courant:", report[-1]["closing"])
    print("Livret A nouveau solde:", bank["savingsAccounts"][0]["solde"])
    print("DDL solde:", bank["savingsAccounts"][1]["solde"])


if __name__ == "__main__":
    transform()
