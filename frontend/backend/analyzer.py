def analyze_appliance(name, watts, hours_per_day, rate):
    if watts <= 0 or rate < 0 or not (0 < hours_per_day <= 24):
        print("Please enter valid appliance details.")
        return

    units_per_month = (watts * hours_per_day * 30) / 1000
    cost_per_month = units_per_month * rate

    print("\n--- Electricity Analysis ---")
    print(f"Appliance: {name}")
    print(f"Monthly usage: {units_per_month:.2f} units")
    print(f"Estimated monthly cost: Rs.{cost_per_month:.2f}")

    if hours_per_day >= 8:
        print("Suggestion: Check whether usage hours can be reduced.")
    else:
        print("Suggestion: Continue monitoring your usage.")


analyze_appliance("Fan", 75, 8, 5)