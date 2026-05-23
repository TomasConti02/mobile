import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

# 1. Load the log file
columns = ['Date', 'Time', 'PID', 'USER', 'PR', 'NI', 'VIRT', 'RES', 'SHR', 'STATUS', 'CPU', 'MEM', 'TIME_PLUS', 'PROCESS']

try:
    df = pd.read_csv('performance_log.txt', sep=r'\s+', names=columns, engine='python')
except FileNotFoundError:
    print("Error: Make sure 'performance_log.txt' is in the same directory as this script!")
    exit()

# 2. Data Cleaning
df['RES_MB'] = df['RES'].str.replace('M', '', regex=False).astype(float)
df['CPU'] = df['CPU'].astype(float)

# Convert the 'Time' column into actual datetime objects so Matplotlib can handle it smartly
df['Time_Obj'] = pd.to_datetime(df['Time'], format='%H:%M:%S')

# --- CHART 1: CPU USAGE OVER TIME ---
fig, ax1 = \
    plt.subplots(figsize=(10, 5))
ax1.plot(df['Time_Obj'], df['CPU'], color='#ff9900', marker='o', linestyle='-', linewidth=2, label='CPU %')

# Force X-axis to display ONLY Minutes and Seconds (MM:SS)
ax1.xaxis.set_major_formatter(mdates.DateFormatter('%M:%S'))
# Automatically optimize the spacing between ticks so they NEVER overlap
fig.autofmt_xdate()

ax1.set_title('CPU Usage Over Time', fontsize=14, fontweight='bold')
ax1.set_xlabel('Time (MM:SS)', fontsize=11)
ax1.set_ylabel('CPU Engagement (%)', fontsize=11)
ax1.grid(True, linestyle='--', alpha=0.5)
ax1.legend(loc='upper left')
plt.tight_layout()

cpu_image = 'cpu_usage_timeline.png'
plt.savefig(cpu_image, dpi=150)
plt.close()
print(f"CPU chart successfully saved as '{cpu_image}'")


# --- CHART 2: MEMORY USAGE OVER TIME ---
fig, ax2 = plt.subplots(figsize=(10, 5))
ax2.plot(df['Time_Obj'], df['RES_MB'], color='#0066cc', marker='s', linestyle='-', linewidth=2, label='Memory RES')

# Force X-axis to display ONLY Minutes and Seconds (MM:SS)
ax2.xaxis.set_major_formatter(mdates.DateFormatter('%M:%S'))
# Automatically optimize the spacing between ticks so they NEVER overlap
fig.autofmt_xdate()

ax2.set_title('Memory Usage (RES) Over Time', fontsize=14, fontweight='bold')
ax2.set_xlabel('Time (MM:SS)', fontsize=11)
ax2.set_ylabel('Memory Occupied (MB)', fontsize=11)
ax2.grid(True, linestyle='--', alpha=0.5)
ax2.legend(loc='upper left')
plt.tight_layout()

mem_image = 'memory_usage_timeline.png'
plt.savefig(mem_image, dpi=150)
plt.close()
print(f"Memory chart successfully saved as '{mem_image}'")
