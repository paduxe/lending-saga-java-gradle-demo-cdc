# C4 Model for System Design: Overview

## Introduction
The C4 model is a lean graphical notation technique for modelling the architecture of software systems. It is based on a structural decomposition of a system into **Context, Containers, Components, and Code**.

The C4 model allows software architects and developers to describe their software architecture at different levels of abstraction, making it easier to communicate with various stakeholders.

## The 4 Levels

### Level 1: System Context
- **Focus**: The high-level view of the system.
- **Audience**: Everyone, including non-technical stakeholders.
- **Content**: Shows the system as a "black box" and how it interacts with users and other systems.

### Level 2: Container Diagram
- **Focus**: Decomposes the system into its technical "containers" (applications, databases, file systems, etc.).
- **Audience**: Technical stakeholders (architects, developers, operations).
- **Content**: Shows the technology stack and how containers communicate (e.g., API calls, message queues).

### Level 3: Component Diagram
- **Focus**: Decomposes a single container into its internal building blocks (components).
- **Audience**: Architects and developers.
- **Content**: Shows how a service is structured internally (controllers, services, repositories, handlers).

### Level 4: Code Diagram
- **Focus**: Deep dive into individual components.
- **Audience**: Developers.
- **Content**: Usually UML class diagrams, ER diagrams, or entity relationship models. (Often omitted in favor of self-documenting code).

---

## Trade-offs of the C4 Model

### Advantages
1.  **Shared Vocabulary**: Provides a standard way to describe architecture, reducing ambiguity.
2.  **Visual Hierarchy**: Allows zooming in and out (from context to code), which helps people understand the system without getting overwhelmed.
3.  **Consistency**: Forces a structured thought process about how parts of the system relate.
4.  **Technology Agnostic**: Can be used with any tech stack or programming language.

### Disadvantages / Challenges
1.  **Maintenance Overhead**: Keeping diagrams up-to-date as the system evolves can be time-consuming if not automated.
2.  **Too Much Detail**: Level 3 and 4 can quickly become overly complex and difficult to read.
3.  **Static View**: C4 diagrams typically show a static structure and may not easily capture dynamic runtime behavior or complex event flows (though Mermaid sequence diagrams can supplement this).
## Navigation
- [Level 1: System Context](level1-context.md)
- [Level 2: Container Diagram](level2-container.md)
- [Level 3: Component Diagram (Orchestrator)](level3-component-orchestrator.md)
- [Sequence Diagrams (Behavior)](sequence-diagrams.md)
