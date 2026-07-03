# Level 1: System Context Diagram

![Level 1 Context](./images/level1-context.png)

The System Context diagram provides a high-level view of the Lending System and its surroundings.

```mermaid
flowchart TD
    User[Customer] --> System[Lending Saga System]
    System --> Email[Email System]
    System --> KYC[KYC Provider]
    System --> Credit[Credit Bureau]
```

## Description
- **Customer**: A borrower who wants to apply for a loan.
- **Lending Saga System**: The core system.
- **Email System**: Sends notifications.
- **KYC Provider**: Identity verification.
- **Credit Bureau**: Credit history check.
