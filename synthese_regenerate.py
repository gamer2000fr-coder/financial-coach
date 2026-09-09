# -*- coding: utf-8 -*-
"""Régénère data/synthese_financier.json depuis le banking_demo_normalized.json (transformé).

Méthode cohérente avec les corrections précédentes :
- income    = crédits reçus SAUF retours depuis l'épargne ("VIR RECU ... MARTIN").
- expenses  = débits de consommation SAUF virements vers l'épargne (LOGITEL / "VERS COMPTE EPARGNE").
- categories/topExpenses = dépenses réelles uniquement.
- balance mensuelle = solde de fin de mois du relevé (déjà plafonné <= 1500 après transformation).
- accounts/credits/savingsTotal alignés sur le maître transformé et compte_epargne.json.
"""
import json
import os

BANK = "data/banking_demo_normalized.json"
EPARGNE = "data/compte_epargne.json"
CREDIT = "data/credit_encours.json"
OUT = "data/synthese_financier.json"


def categorize(label):
    L = (label or "").upper()
    def has(*ws): return any(w in L for w in ws)
    if has("SALAIRE", "PAIE", "REMUNERATION"): return "SALARY"
    if has("LOYER", "IMMO", "CHARGE IMMOBILIERE"): return "HOUSING"
    if has("AUCHAN", "CARREFOUR", "MONOPRIX", "INTERMARCHE", "LECLERC", "CRF", "SUPERMAR"): return "GROCERIES"
    if has("RESTAUR", "GOURMANDISE", "BOULANGER", "PATISSERIE", "CAFE", "MC DONALD", "KFC"): return "RESTAURANT"
    if has("AMAZON", "CASTORAMA", "FNAC", "DACIA", "DECATHLON", "COMMERCE ELECTRONIQUE"): return "SHOPPING"
    if has("EDF", "ENGIE", "ELECTRIC", "EAU "): return "UTILITIES"
    if has("ORANGE", "FREE", "SFR", "BOUYGUES", "TELECOM"): return "TELECOM"
    if has("TOTAL", "ESSO", "SHELL", "CARBURANT", "BP "): return "TRANSPORT"
    if has("SNCF", "RATP", "NAVIGO", "UBER", "BOLT", "PAYBYPHONE", "PARKING"): return "TRANSPORT"
    if has("IMPOT", "TRESOR", "TAX"): return "TAXES"
    if has("ASSURANCE", "MAIF", "AXA", "MACIF"): return "INSURANCE"
    if has("INTERETS", "LIVRET", "EPARGNE"): return "SAVINGS"
    if has("CREDIT", "PRET", "MENSUALITE"): return "LOAN"
    if "VIR RECU" in L: return "TRANSFER_IN"
    if "VIR EMIS" in L or "VIREMENT" in L: return "TRANSFER_OUT"
    if has("PHARMACIE", "MEDECIN", "DOCTEUR", "SANTE"): return "HEALTH"
    return "OTHER"


def is_save_out(label):
    """Débit = virement vers l'épargne (Logitel existant OU virement ajouté de plafonnement)."""
    U = (label or "").upper()
    return "LOGITEL" in U or "VERS COMPTE EPARGNE" in U


def is_save_in(label):
    """Crédit = retour depuis l'épargne (virement reçu de soi / de la famille MARTIN)."""
    U = (label or "").upper()
    return "VIR RECU" in U and "MARTIN" in U


def closing_balance(month):
    for key, val in month.items():
        if key.startswith("nouveau_solde"):
            return val
    return None


def iso_date(dd_mm_yyyy):
    try:
        d, m, y = dd_mm_yyyy.split("/")
        return f"{y}-{m}-{d}"
    except Exception:
        return dd_mm_yyyy


def main():
    bank = json.load(open(BANK, encoding="utf-8"))
    months = bank["accounts"][0]["transactions"]["months"]
    account_id = bank["accounts"][0]["accountId"]

    savings = json.load(open(EPARGNE, encoding="utf-8"))
    savings_total = round(sum(a.get("solde") or 0 for a in savings["savingsAccounts"]), 2)

    credit = json.load(open(CREDIT, encoding="utf-8"))
    credits = []
    for c in credit["credits"]:
        fin = c.get("fin")
        if fin and "/" in fin:
            fin = iso_date(fin)
        credits.append({"produit": c.get("produit"), "mensualite": c.get("mensualite"), "fin": fin})

    monthly = []
    incomes, expenses, balances = [], [], []
    for month in months:
        income = 0.0
        expense = 0.0
        cat = {}
        top = []
        for op in month["operations"]:
            label = op.get("nature_operation", "")
            if is_save_out(label):
                continue
            if is_save_in(label):
                continue
            if op.get("credit") is not None:
                income += op["credit"]
            if op.get("debit") is not None:
                expense += op["debit"]
                c = categorize(label)
                cat[c] = cat.get(c, 0) + op["debit"]
                top.append((op["debit"], label, c, op.get("date")))
        bal = closing_balance(month)
        incomes.append(income)
        expenses.append(expense)
        balances.append(bal)
        top.sort(reverse=True)
        monthly.append({
            "month": month["periode"],
            "income": round(income, 2),
            "expenses": round(expense, 2),
            "savings": round(income - expense, 2),
            "balance": round(bal, 2) if bal is not None else None,
            "categories": {k: round(v, 2) for k, v in cat.items() if v > 0},
            "topExpenses": [
                {"date": iso_date(t[3]) if t[3] else None, "label": t[1],
                 "amount": round(t[0], 2), "category": t[2]} for t in top[:3]
            ],
        })

    avg_income = sum(incomes) / len(incomes) if incomes else 0
    avg_expense = sum(expenses) / len(expenses) if expenses else 0
    final_balance = balances[-1] if balances else 0

    first = months[0]["periode"]
    last = months[-1]["periode"]

    def period_bounds(periode):
        m, y = periode.split("/")
        return f"{y}-{m}"

    syn = {
        "period": {"from": period_bounds(first) + "-01", "to": period_bounds(last) + "-31"},
        "accounts": [{"accountId": account_id, "balance": round(final_balance, 2) if final_balance else 0}],
        "credits": credits,
        "savingsTotal": savings_total,
        "monthlySummaries": monthly,
        "global": {
            "averageMonthlyIncome": round(avg_income, 2),
            "averageMonthlyExpenses": round(avg_expense, 2),
            "currentAccountBalance": round(final_balance, 2) if final_balance else 0,
            "savingsTotal": savings_total,
            "monthlyLoanPayments": round(sum(c.get("mensualite") or 0 for c in credits), 2),
        },
    }
    json.dump(syn, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("OK ->", OUT)
    print("months:", len(monthly), "| final balance:", round(final_balance, 2),
          "| savingsTotal:", savings_total, "| credits:", credits)
    for m in monthly:
        print(f"  {m['month']}: income={m['income']:.2f} expenses={m['expenses']:.2f} "
              f"capEpargne={m['savings']:.2f} solde={m['balance']}")


if __name__ == "__main__":
    main()
