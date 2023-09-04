package com.splunk.splunkjenkins;

import com.cloudbees.workflow.rest.external.ChunkVisitor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Record workspace node during visitation
 * Not thread safe
 */
public class WorkspaceChunkVisitor extends ChunkVisitor {
    private static final Logger LOG = Logger.getLogger(WorkspaceChunkVisitor.class.getName());
    // key is node id, value is jenkins worker node name
    Map<String, String> workspaceNodes = new HashMap<>();
    // key is flowode id, value is parent stage id
    Map<String, String> parallelNodes = new HashMap<>();
    String execNodeName = null;
    String execNodeStartId = null;
    String enclosingStageName = null;
    String enclosingStageId = null;
    String currentParallelNodeStartId = "";

    public WorkspaceChunkVisitor(@NonNull WorkflowRun run) {
        super(run);
    }

    @Override
    public void atomNode(@CheckForNull FlowNode before, @NonNull FlowNode atomNode, @CheckForNull FlowNode after, @NonNull ForkScanner scan) {
        //reverse-order, traverse from end node to start node
        try {
            recordExecNode(atomNode);
            recordStageNode(atomNode);
            recordParallelNode(scan);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "failed to extract pipeline info", ex);
        }
        super.atomNode(before, atomNode, after, scan);
    }

    /**
     * store stage node info
     *
     * @param atomNode flow node
     */
    private void recordStageNode(FlowNode atomNode) {
        // record stage name for parallel run inside stage
        if (enclosingStageName == null) {
            StepStartNode stageNode = getPipelineBlockBoundaryStartNode(atomNode, "stage");
            if (stageNode != null) {
                ArgumentsAction argumentsAction = stageNode.getAction(ArgumentsAction.class);
                if (argumentsAction != null) {
                    enclosingStageName = "" + argumentsAction.getArgumentValue("name");
                } else {
                    enclosingStageName = "";
                }
                enclosingStageId = stageNode.getId();
                LOG.log(Level.FINE, "found stage node id={0}, name={1}", new String[]{enclosingStageId, enclosingStageName});
            }
        } else if (atomNode instanceof StepStartNode && atomNode.getId().equals(enclosingStageId)) {
            enclosingStageName = null;
        }
    }

    /**
     * store the jenkins node name where pipeline ran
     *
     * @param atomNode flow  node
     */
    private void recordExecNode(FlowNode atomNode) {
        if (execNodeName == null) {
            StepStartNode nodeStep = getPipelineBlockBoundaryStartNode(atomNode, "node");
            if (nodeStep != null) {
                //WorkspaceAction is recorded in node start
                WorkspaceAction workspaceAction = nodeStep.getAction(WorkspaceAction.class);
                if (workspaceAction != null) {
                    //store which jenkins node it is built on
                    execNodeName = workspaceAction.getNode();
                    execNodeStartId = nodeStep.getId();
                    if (StringUtils.isEmpty(execNodeName)) {
                        execNodeName = Constants.BUILT_IN_NODE;
                    }
                    LOG.log(Level.FINE, "found workspace node id={0}, name={1}", new String[]{execNodeStartId, execNodeName});
                }
            }
        } else if (atomNode instanceof StepStartNode && atomNode.getId().equals(execNodeStartId)) {
            execNodeName = null;
        }
        if (execNodeName != null) {
            workspaceNodes.put(atomNode.getId(), execNodeName);
        }
    }

    /**
     * store parallel node info
     *
     * @param scan Scanner
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private void recordParallelNode(@NonNull ForkScanner scan) {
        if (scan.getCurrentParallelStartNode() != null) {
            //store parallel node start id
            String nodeId = scan.getCurrentParallelStartNode().getId();
            if (nodeId != null && !nodeId.equals(currentParallelNodeStartId)) {
                currentParallelNodeStartId = nodeId;
                //hacky way to calc the parallelBlockNodeId
                try {
                    String parallelBlockId = "" + (1 + Integer.parseInt(nodeId));
                    parallelNodes.put(parallelBlockId, enclosingStageName);
                } catch (NumberFormatException ex) {
                    //ignore
                }
            }
        }
    }

    /**
     * Check whether it is an enclose functional node (with BodyInvocationAction)
     * Check whether it is an enclosed functional node (with BodyInvocationAction)
     *
     * @param atomNode
     * @param functionName
     * @return
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private StepStartNode getPipelineBlockBoundaryStartNode(FlowNode atomNode, String functionName) {
        StepStartNode startNode = null;
        // it should have BodyInvocationAction
        if (atomNode instanceof StepEndNode
                && ((StepEndNode) atomNode).getStartNode() != null
                && ((StepEndNode) atomNode).getStartNode().getDescriptor() != null
                && ((StepEndNode) atomNode).getStartNode().getDescriptor().getFunctionName().equals(functionName)) {
            StepStartNode blockStart = ((StepEndNode) atomNode).getStartNode();
            if (blockStart.getParents().size() < 1) {
                return null;
            }
            FlowNode boundaryNode = blockStart.getParents().get(0);
            if (boundaryNode instanceof StepStartNode) {
                startNode = (StepStartNode) boundaryNode;
            } else if (boundaryNode instanceof FlowStartNode) {
                //special handling for stage node
                startNode = blockStart;
            }
        }
        return startNode;
    }

    public Map<String, String> getWorkspaceNodes() {
        return workspaceNodes;
    }

    public Map<String, String> getParallelNodes() {
        return parallelNodes;
    }
}

