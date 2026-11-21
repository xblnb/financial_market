package com.example.financialmarket.engine.banking;

public class CreditSystem {
    private double totalPrivateDebt;
    private double totalPublicDebt;
    private double nplRatio;
    private double creditGrowth;
    private double previousTotalDebt = 0;

    public void update(BankManager bankManager, Government government) {
        totalPrivateDebt = 0;
        int totalLoans = 0;
        int defaultedLoans = 0;
        for (Bank bank : bankManager.getAllBanks()) {
            totalPrivateDebt += bank.getTotalLoans();
            for (Loan loan : bank.getLoans()) {
                totalLoans++;
                if (loan.getStatus() == Loan.LoanStatus.DEFAULTED) defaultedLoans++;
            }
        }
        nplRatio = totalLoans > 0 ? (double) defaultedLoans / totalLoans : 0;
        totalPublicDebt = government.getTotalDebt();
        double totalDebt = totalPrivateDebt + totalPublicDebt;
        creditGrowth = previousTotalDebt > 0 ? (totalDebt - previousTotalDebt) / previousTotalDebt : 0;
        previousTotalDebt = totalDebt;
    }

    public double getTotalPrivateDebt() { return totalPrivateDebt; }
    public double getTotalPublicDebt() { return totalPublicDebt; }
    public double getNplRatio() { return nplRatio; }
    public double getCreditGrowth() { return creditGrowth; }
    public double getTotalDebt() {
        return totalPrivateDebt + totalPublicDebt;
    }
}