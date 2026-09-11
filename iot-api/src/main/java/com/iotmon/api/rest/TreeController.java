package com.iotmon.api.rest;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.tree.MonitoringTree;
import com.iotmon.domain.tree.NodeKind;
import com.iotmon.domain.tree.Rollup;
import com.iotmon.domain.tree.TreeNode;
import com.iotmon.infrastructure.persistence.MonitoringTreeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 監控樹。
 *
 * <p>回傳的每一層子節點都已經依 {@code (sortOrder, id)} 排好，
 * 這是契約對前端的保證——前端不排序、也不用 Map/Set 承接。
 * 排序只在領域層的 {@code MonitoringTree.build()} 做一次，這裡原樣輸出。
 */
@RestController
@RequestMapping("/api/v1/tree")
public class TreeController {

    private final MonitoringTreeRepository repository;

    public TreeController(MonitoringTreeRepository repository) {
        this.repository = repository;
    }

    /** 整棵樹，含每個節點的 rollup。 */
    @GetMapping
    public List<NodeResponse> tree() {
        return build(repository.findAll());
    }

    /** 該節點與其子樹。找不到回 404。 */
    @GetMapping("/{nodeId}")
    public ResponseEntity<NodeResponse> subtree(@PathVariable long nodeId) {
        List<NodeResponse> roots = build(repository.findSubtree(nodeId));
        return roots.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(roots.get(0));
    }

    /**
     * 從根到該節點，由上到下。前端用來畫麵包屑、以及告警推播時定位要更新的整條路徑。
     * 這條路徑不帶 rollup——它是「這個節點在哪裡」的答案，不是「哪裡在響」。
     */
    @GetMapping("/{nodeId}/ancestors")
    public ResponseEntity<List<PlainNode>> ancestors(@PathVariable long nodeId) {
        List<TreeNode> chain = repository.findAncestors(nodeId);
        if (chain.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(chain.stream().map(PlainNode::from).toList());
    }

    /**
     * 新增節點。嵌套規則在領域層的 {@code TreeNode.attachUnder} 檢查，
     * 違反回 400 並說明是哪條規則；資料庫的觸發器是第二道防線。
     */
    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public PlainNode create(@RequestBody CreateNodeRequest request) {
        NodeKind kind = Params.enumOf(NodeKind.class, request.kind(), "節點類型");
        DeviceId deviceId = request.deviceId() == null ? null : DeviceId.of(request.deviceId());
        long order = request.sortOrder() == null ? 0 : request.sortOrder();

        TreeNode candidate;
        if (request.parentId() == null) {
            candidate = TreeNode.equipment(null, request.name(), order);
        } else {
            TreeNode parent = repository.findById(request.parentId())
                    .orElseThrow(() -> new IllegalArgumentException("父節點不存在：" + request.parentId()));
            candidate = TreeNode.attachUnder(parent, null, kind, request.name(), deviceId, order);
        }
        return PlainNode.from(repository.insert(candidate, request.parentId()));
    }

    /** 同層重排：只改 sortOrder，不動 path。 */
    @PatchMapping("/nodes/{nodeId}/order")
    public ResponseEntity<?> reorder(@PathVariable long nodeId, @RequestBody ReorderRequest request) {
        return repository.updateSortOrder(nodeId, request.sortOrder())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * 同層重新編號。順序不變，只是把 sort_order 重新拉開間隔。
     *
     * <p>反覆在同一處插入會把間隔用完，前端算不出「上移一格」該用什麼值；
     * 沒有這個端點的話，使用者在那個錯誤訊息前面就無路可走了。
     */
    @PostMapping("/renumber")
    public RenumberResponse renumber(@RequestBody RenumberRequest request) {
        if (request.parentId() != null && repository.findById(request.parentId()).isEmpty()) {
            throw ApiException.notFound("父節點不存在：" + request.parentId());
        }
        return new RenumberResponse(request.parentId(), repository.renumber(request.parentId()));
    }

    /** parentId 為 null 代表根節點那一層 */
    public record RenumberRequest(Long parentId) {
    }

    public record RenumberResponse(Long parentId, int renumbered) {
    }

    private List<NodeResponse> build(List<TreeNode> nodes) {
        Map<Long, Rollup> own = repository.firingAlarmsByNode();
        return MonitoringTree.build(nodes, own).stream().map(NodeResponse::from).toList();
    }

    public record NodeResponse(long id, String kind, String name, String deviceId, long sortOrder,
                               RollupResponse rollup, List<NodeResponse> children) {
        static NodeResponse from(MonitoringTree.Branch branch) {
            TreeNode n = branch.node();
            return new NodeResponse(n.id(), n.kind().name(), n.name(),
                    n.device().map(DeviceId::value).orElse(null), n.sortOrder(),
                    RollupResponse.from(branch.rollup()),
                    branch.children().stream().map(NodeResponse::from).toList());
        }
    }

    /** severity 為 null 代表子樹內沒有任何未解除告警 */
    public record RollupResponse(String severity, int firing) {
        static RollupResponse from(Rollup r) {
            return new RollupResponse(r.severity().map(Enum::name).orElse(null), r.firing());
        }
    }

    public record PlainNode(long id, String kind, String name, String deviceId, long sortOrder) {
        static PlainNode from(TreeNode n) {
            return new PlainNode(n.id(), n.kind().name(), n.name(),
                    n.device().map(DeviceId::value).orElse(null), n.sortOrder());
        }
    }

    public record CreateNodeRequest(String kind, String name, Long parentId, String deviceId, Long sortOrder) {
    }

    public record ReorderRequest(long sortOrder) {
    }
}
