# CNT5106C-Project
## Group Members
Raghav Rathi, Vandit Shah, Aryan Pasupuletti



## Initial Set Up
1. Connect to UF's network though the VPN (info found here: https://it.ufl.edu/ict/documentation/network-infrastructure/vpn/)
2. SSH into one of the machines. You will need to use your gatorlink password to authenicate. We tried on these machines.
1001 thunder.cise.ufl.edu 6408 1
1002 thunder.cise.ufl.edu 6409 0
1003 thunder.cise.ufl.edu 6410 0
1004 thunder.cise.ufl.edu 6411 0
1005 storm.cise.ufl.edu   6412 0
1006 storm.cise.ufl.edu   6413 0

3. Clone the github repo. You will need to create a personal access token to be able to do this (link: https://github.com/settings/tokens). First clone the repo, then it will prompt you for you username followed by the password (which is your personal access token).
4. Make sure all the folders that will be written to in the code exist (ex: 1001)

## How To Run On The CISE Servers
1. Connect to UF's network though the VPN
2. SSH into the machines found in peerInfo.cfg in different terminal windows. 


3. In the first machine compile peerProcess.java and then run with the first peer id value. For the first machine you would run:

javac peerProcess.java
java peerProcess 1001
```
4. Repeat this process for all the other machines, in the order in peerInfo.cfg

github repo - github.com/ShahVandit/cn_project

Video - https://uflorida-my.sharepoint.com/personal/shahvandit_ufl_edu/_layouts/15/stream.aspx?id=%2Fpersonal%2Fshahvandit%5Fufl%5Fedu%2FDocuments%2Fscreen%2Drecorder%2Dwed%2Ddec%2D04%2D2024%2D23%2D53%2D05%2Ewebm&referrer=StreamWebApp%2EWeb&referrerScenario=AddressBarCopied%2Eview%2E2eef0675%2D8709%2D4b25%2D9519%2D4ef1b83f7d42