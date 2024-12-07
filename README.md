# CNT5106C-Project
## Group Members
Raghav Rathi, Vandit Shah, Aryan Pasupuletti


# P2P File Transfer

This project simulates a Peer-to-Peer (P2P) file-sharing system using Java. It enables multiple peers to exchange pieces of a file until all peers have the complete file.

---

## Prerequisites

Ensure the following tools and configurations are in place:
1. **VPN Connection**: Connect to the UF VPN to access CISE servers remotely.
2. **CISE Account**: Ensure you have a valid CISE username and password for SSH access.
3. **Required Tools**:
   - **Java Development Kit (JDK)**: For compiling and running the Java program.
   - **Git**: For cloning and managing the repository.

---

Step-by-Step Instructions

### 1. Connect to the UF VPN
- Follow UF's official VPN setup instructions: [UF VPN Guide](https://it.ufl.edu/ict/documentation/network-infrastructure/vpn/).
- Confirm your connection by pinging a CISE server (e.g., Thunder):
  ```bash
  ping thunder.cise.ufl.edu
  
###  2. SSH into the Machines

We have used 6 machines to run the project: 4 Thunder servers and 2 Storm servers. SSH into each machine as follows:
ssh your_username@thunder.cise.ufl.edu

## 3. Prepare the Configuration Files
- Use the following configuration to set up your peers:
1001 thunder.cise.ufl.edu 6408 1
1002 thunder.cise.ufl.edu 6409 0
1003 thunder.cise.ufl.edu 6410 0
1004 thunder.cise.ufl.edu 6411 0
1005 storm.cise.ufl.edu   6412 0
1006 storm.cise.ufl.edu   6413 0

## 4. Clone the Repository
-git clone https://github.com/ShahVandit/cn_project.git
##	5.	Navigate to the project directory and run the following commands:
- cd ~/P2PFileTransfer/project
- javac peerProcess.java
- In different machines run following commands:
- java peerProcess 1001
- java peerProcess 1002
- java peerProcess 1003
- java peerProcess 1004
- java peerProcess 1005
- java peerProcess 1006
