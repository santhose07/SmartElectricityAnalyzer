import java.util.Scanner;

public class Main {
    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);
        System.out.println("==========================================");
System.out.println("     SMART ELECTRICITY USAGE ANALYZER");
System.out.println("==========================================");
System.out.println("        Welcome to the Energy Analyzer");
System.out.println();

        System.out.print("Enter number of appliances: ");
        int numberOfAppliances = sc.nextInt();
        while (numberOfAppliances <= 0) {
    System.out.print("Please enter a valid number of appliances: ");
    numberOfAppliances = sc.nextInt();
}
        sc.nextLine();

        double totalUnits = 0;
        double totalBill = 0;
        System.out.print("Enter electricity rate per unit (Rs.): ");
double ratePerUnit = sc.nextDouble();
sc.nextLine();
        double highestUnits = 0;
String highestAppliance = "";

        for (int i = 1; i <= numberOfAppliances; i++) {

            System.out.println();
            System.out.println("----- Appliance " + i + " -----");

            System.out.print("Enter appliance name: ");
            String applianceName = sc.nextLine();

            System.out.print("Enter power consumption (Watts): ");
            double power = sc.nextDouble();
            while (power <= 0) {
    System.out.print("Please enter a valid power value: ");
    power = sc.nextDouble();
}

            System.out.print("Enter usage hours per day: ");
            double hours = sc.nextDouble();
            while (hours <= 0 || hours > 24) {
    System.out.print("Please enter valid hours (1-24): ");
    hours = sc.nextDouble();
}
            sc.nextLine();

            double monthlyUnits = (power * hours * 30) / 1000;

            
            double estimatedBill = monthlyUnits * ratePerUnit;

            totalUnits = totalUnits + monthlyUnits;
            totalBill = totalBill + estimatedBill;
            if (monthlyUnits > highestUnits) {
    highestUnits = monthlyUnits;
    highestAppliance = applianceName;
}

            System.out.println("Monthly Electricity Usage: "
                    + monthlyUnits + " units");

            System.out.println("Estimated Monthly Bill: Rs."
                    + estimatedBill);
        }

        System.out.println();
        System.out.println("========== TOTAL ==========");
        System.out.println("Total Monthly Usage: " + totalUnits + " units");
        System.out.println("Total Estimated Bill: Rs." + totalBill);
        System.out.println("Highest Consuming Appliance: " + highestAppliance);
System.out.println("Highest Consumption: " + highestUnits + " units");
System.out.println();
System.out.println("----- Energy Saving Suggestion -----");

if (highestAppliance.equalsIgnoreCase("AC")) {
    System.out.println("Suggestion: Reduce AC usage or increase the temperature setting.");
} else if (highestAppliance.equalsIgnoreCase("Fan")) {
    System.out.println("Suggestion: Reduce fan usage when it is not needed.");
} else if (highestAppliance.equalsIgnoreCase("TV")) {
    System.out.println("Suggestion: Switch off the TV when not in use.");
} else if (highestAppliance.equalsIgnoreCase("Light")) {
    System.out.println("Suggestion: Switch off lights when not needed.");
} else {
    System.out.println("Suggestion: Try to reduce the usage time of this appliance.");
}
System.out.println();
System.out.print("Enter previous month electricity usage (units): ");
double previousMonthUnits = sc.nextDouble();
while (previousMonthUnits < 0) {
    System.out.print("Please enter a valid usage value: ");
    previousMonthUnits = sc.nextDouble();
}
double difference = previousMonthUnits - totalUnits;
double percentageChange = (Math.abs(difference) / previousMonthUnits) * 100;
double moneySaved = Math.abs(difference) * ratePerUnit;

if (difference > 0) {
    System.out.println("You reduced your electricity usage by "
            + difference + " units.");
            System.out.println("Usage Reduction Percentage: "
        + percentageChange + "%");
        System.out.println("Estimated Money Saved: Rs." + moneySaved);
} else if (difference < 0) {
    System.out.println("Your electricity usage increased by "
            + (-difference) + " units.");
} else {
    System.out.println("Your electricity usage is the same as last month.");
}
System.out.println();
System.out.println("----- Energy Efficiency Status -----");

if (difference > 0) {
    System.out.println("Status: GOOD");
    System.out.println("You are using electricity more efficiently.");
} else if (difference < 0) {
    System.out.println("Status: NEED IMPROVEMENT");
    System.out.println("Try to reduce unnecessary electricity usage.");
} else {
    System.out.println("Status: SAME");
    System.out.println("Your electricity usage is unchanged.");
}
System.out.println();
System.out.println("========== FINAL ELECTRICITY REPORT ==========");
System.out.println("Number of Appliances: " + numberOfAppliances);
System.out.println("Total Monthly Usage: " + totalUnits + " units");
System.out.println("Total Estimated Bill: Rs." + totalBill);
System.out.println("Highest Consuming Appliance: " + highestAppliance);
System.out.println("Highest Consumption: " + highestUnits + " units");
System.out.println("Previous Month Usage: " + previousMonthUnits + " units");
System.out.println("Current Month Usage: " + totalUnits + " units");
        sc.close();
    }
}