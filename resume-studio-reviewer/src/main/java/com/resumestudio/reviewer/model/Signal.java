package com.resumestudio.reviewer.model;


import com.resumestudio.reviewer.model.enums.ImpactLevel;
import com.resumestudio.reviewer.model.enums.SignalFriction;
import com.resumestudio.reviewer.model.enums.SignalStatus;

/**
 * A single evaluated signal — what the recruiter would notice,
 * what it means, and how much friction it caused.
 */
public class Signal {

    private String id;              // e.g. "must_haves_visible", "yoe_fit"
    private String label;           // human label e.g. "Must-have skills visible"
    private SignalStatus status;    // PASS, WARN, FAIL
    private SignalFriction friction; // how hard it was to extract this signal
    private String observation;     // what was found e.g. "Java only in 2021 bullet"
    private String interpretation;  // what it means to a recruiter
    private ImpactLevel impact;

    public Signal() {}

    public Signal(String id, String label, SignalStatus status, SignalFriction friction,
                  String observation, String interpretation, ImpactLevel impact) {
        this.id = id;
        this.label = label;
        this.status = status;
        this.friction = friction;
        this.observation = observation;
        this.interpretation = interpretation;
        this.impact = impact;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public SignalStatus getStatus() { return status; }
    public void setStatus(SignalStatus status) { this.status = status; }

    public SignalFriction getFriction() { return friction; }
    public void setFriction(SignalFriction friction) { this.friction = friction; }

    public String getObservation() { return observation; }
    public void setObservation(String observation) { this.observation = observation; }

    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }

    public ImpactLevel getImpact() { return impact; }
    public void setImpact(ImpactLevel impact) { this.impact = impact; }
}
