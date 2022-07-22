package com.hanghae99.finalproject.service;

import com.amazonaws.services.s3.model.S3Object;
import com.hanghae99.finalproject.exceptionHandler.CustumException.CustomException;
import com.hanghae99.finalproject.model.dto.requestDto.*;
import com.hanghae99.finalproject.model.dto.responseDto.*;
import com.hanghae99.finalproject.model.entity.*;
import com.hanghae99.finalproject.model.repository.*;
import com.hanghae99.finalproject.model.resultType.*;
import com.hanghae99.finalproject.util.UserinfoHttpRequest;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.hanghae99.finalproject.exceptionHandler.CustumException.ErrorCode.*;
import static com.hanghae99.finalproject.model.resultType.CategoryType.*;
import static com.hanghae99.finalproject.model.resultType.FileUploadType.BOARD;

@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final FolderRepository folderRepository;
    private final UserinfoHttpRequest userinfoHttpRequest;
    private final S3Uploader s3Uploader;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;

    @Transactional
    public MessageResponseDto boardSave(BoardRequestDto boardRequestDto, HttpServletRequest request) {
        Image saveImage = new Image();

        if (boardRequestDto.getBoardType() == BoardType.LINK) {
            boardRequestDto.ogTagToBoardRequestDto(
                    thumbnailLoad(boardRequestDto.getLink()),
                    boardRequestDto.getLink()
            );

            if (!boardRequestDto.getImgPath().equals("") && boardRequestDto.getImgPath() != null) {
                boardRequestDto.updateImagePath(
                        s3Uploader.upload(
                                BOARD.getPath(),
                                boardRequestDto.getImgPath()
                        ).getUrl()
                );
            }

        } else if (boardRequestDto.getBoardType() == BoardType.MEMO) {
            boardRequestDto.updateTitle(new SimpleDateFormat(DateType.YEAR_MONTH_DAY.getPattern()).format(new Date()));
        }

        Users findUser = userinfoHttpRequest.userFindByToken(request);
        Folder findFolder = folderRepository.findByUsersAndName(findUser, "무제");

        Board saveBoard = boardRepository.save(
                new Board(
                        findFolder.getBoardCnt(),
                        boardRequestDto,
                        findUser,
                        findFolder
                )
        );

        if (boardRequestDto.getBoardType() == BoardType.LINK) {
            saveImage = imageRepository.save(
                    new Image(
                            saveBoard,
                            ImageType.OG
                    )
            );
        }

        findFolder.updateBoardCnt(findFolder.getBoardCnt() + 1);
        findUser.updateBoardCnt(findUser.getBoardCnt() + 1);

        return new MessageResponseDto(
                200,
                "저장이 완료 되었습니다.",
                new BoardResponseDto(
                        saveBoard,
                        findFolder,
                        new ImageRequestDto(saveImage)
                )
        );
    }

    @Transactional
    public void boardUpdate(Long id, BoardRequestDto boardRequestDto, HttpServletRequest request) {
        Board board = boardFindById(id);
        List<Image> images = imageRepository.findByBoard(board);

        userinfoHttpRequest.userAndWriterMatches(
                board.getUsers().getId(),
                userinfoHttpRequest.userFindByToken(request).getId()
        );

        if (!board.getFolder().getId().equals(boardRequestDto.getFolderId())) {
            boardInFolder(boardRequestDto.getFolderId(), new FolderRequestDto(id), request);
        }

        if (boardRequestDto.getBoardType() == BoardType.LINK && boardRequestDto.getImage().getImageType() == ImageType.OG) {
            if (!board.getImgPath().equals(boardRequestDto.getImage().getImgPath())) {
                S3Object removeImageUrl = s3Uploader.selectImage(
                        BOARD.getPath(),
                        board.getImgPath()
                );
                if (Optional.ofNullable(removeImageUrl).isPresent()) {
                    s3Uploader.fileDelete(removeImageUrl.getKey());
                }
                S3Object addImageUrl = s3Uploader.selectImage(BOARD.getPath(), boardRequestDto.getImgPath());
                if (!Optional.ofNullable(addImageUrl).isPresent()) {
                    boardRequestDto.updateImagePath(
                            s3Uploader.upload(
                                    BOARD.getPath(),
                                    boardRequestDto.getImage().getImgPath()
                            ).getUrl());
                }
                findImageEqualsDtoImage(images, boardRequestDto, board);
            }
        } else if (boardRequestDto.getBoardType() == BoardType.LINK && boardRequestDto.getImage().getImageType() != ImageType.OG) {
            if (!board.getImgPath().equals(boardRequestDto.getImage().getImgPath())) {
                if (!imageRepository.existsByImageTypeAndBoard(boardRequestDto.getImage().getImageType(), board)) {
                    imageRepository.save(new Image(board, boardRequestDto.getImage()));
                    boardRequestDto.updateImagePath(boardRequestDto.getImage().getImgPath());
                } else {
                    boardRequestDto.updateImagePath(boardRequestDto.getImage().getImgPath());
                    findImageEqualsDtoImage(images, boardRequestDto, board);
                }
            }
        }

        board.update(boardRequestDto);
    }

    @Transactional
    public void boardDelete(List<BoardRequestDto> boardRequestDto, HttpServletRequest request, Long folderId) {
        Users users = userinfoHttpRequest.userFindByToken(request);
        List<Long> longs = boardRequestDto.stream()
                .map(BoardRequestDto::getId)
                .collect(Collectors.toList());

        List<Board> boardList = boardAllFindById(longs);
        for (Board board : boardList) {
            userinfoHttpRequest.userAndWriterMatches(board.getUsers().getId(), users.getId());
        }

        imageRepository.deleteAllByBoardIdIn(longs);
        boardRepository.deleteAllById(longs);

        users.setBoardCnt(users.getBoardCnt() - boardList.size());

        List<Board> removeAfterBoardList = boardFindByUsersIdOrderByBoardOrderAsc(users.getId(), folderId);

        Long cnt = 1L;
        for (Board board : removeAfterBoardList) {
            board.setBoardOrder(cnt);
            cnt++;
        }
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("찾는 폴더가 없습니다."));

        folder.setBoardCnt(folder.getBoardCnt() - longs.size());
    }

    public Board boardFindById(Long id) {
        return boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("해당 글을 찾지 못했습니다."));
    }

    public List<Board> boardFindByUsersIdOrderByBoardOrderAsc(Long userId, Long folderId) {
        return boardRepository.findAllByUsersIdOrderByBoardOrderAsc(userId, folderId);
    }

    public List<Board> boardAllFindById(List<Long> boardIdList) {
        return boardRepository.findAllById(boardIdList);

    }

    public List<Board> findAllById(List<Long> longs) {
        return boardRepository.findAllById(longs);
    }

    public List<Board> boardDeleteByFolderId(List<Long> folderIdList) {
        return boardRepository.deleteAllByFolderIdIn(folderIdList);
    }

    public void statusUpdateByFolderId(Long id, FolderRequestDto folderRequestDto) {
        Optional<Board> board = boardRepository.findByFolderId(id);

        if (board.isPresent()) {
            board.get().updateStatus(folderRequestDto);
        }

    }

    public OgResponseDto thumbnailLoad(String url) {
        OgResponseDto ogResponseDto;
        try {
            Document doc = Jsoup.connect(url).get();
            String title = doc.select("meta[property=og:title]").attr("content");
            String image = doc.select("meta[property=og:image]").attr("content");
            String description = doc.select("meta[property=og:description]").attr("content");
            ogResponseDto = new OgResponseDto(title, image, description);
        } catch (Exception e) {
            ogResponseDto = new OgResponseDto("", "", "");
        }
        return ogResponseDto;

    }

    //    public Board findShareBoard(Long boardId, HttpServletRequest request) {
    //        return boardRepository.findByIdAndUsersIdNot(boardId, userinfoHttpRequest.userFindByToken(request).getId()).orElseThrow(()
    //                -> new RuntimeException("원하는 폴더를 찾지 못했습니다."));
    //    }
    //    @Transactional
    //    public void cloneBoard(Long boardId, HttpServletRequest request) {
    //        Users users = userinfoHttpRequest.userFindByToken(request);
    //        Board board = findShareBoard(boardId, request);
    //        BoardRequestDto boardRequestDto = new BoardRequestDto(board);
    //        Folder folder = folderRepository.findByUsersAndName(users , "무제");
    //        Board board1 = new Board(boardRequestDto, users,folder);
    //        boardRepository.save(board1);
    //        folder.setBoardCnt(folder.getBoardCnt() + 1);
    //        users.setBoardCnt(users.getBoardCnt() + 1);
    //    }

    @Transactional
    public void cloneBoards(List<BoardRequestDto> boards, HttpServletRequest request) {
        Users users = userinfoHttpRequest.userFindByToken(request);

        Folder folder = folderRepository.findByUsersAndName(users, "무제");

        List<Long> LongList = boards.stream()
                .map(BoardRequestDto::getId)
                .collect(Collectors.toList());

        List<Board> boardList = boardRepository.findAllByIdIn(LongList);

        for (Board board : boardList) {

            boardRepository.save(new Board(board, users, folder));
            folder.setBoardCnt(folder.getBoardCnt() + 1);
            users.setBoardCnt(users.getBoardCnt() + 1);
        }
    }

    @Transactional
    public void boardOrderChange(OrderRequestDto orderRequestDto, HttpServletRequest request) {
        Board board = boardRepository.findById(orderRequestDto.getBoardId())
                .orElseThrow(() -> new RuntimeException("없는 게시물입니다."));
        Users users = userinfoHttpRequest.userFindByToken(request);

        if (board.getUsers().getId() != users.getId()) {
            throw new RuntimeException("글쓴이가 아닙니다.");
        }

        if (board.getBoardOrder() == orderRequestDto.getAfterOrder() || orderRequestDto.getAfterOrder() > users.getBoardCnt()) {
            throw new RuntimeException("잘못된 정보입니다. : 기존 order : " + board.getBoardOrder() + "바꾸는 order : " + orderRequestDto.getAfterOrder() + "forder 최종 order : " + users.getBoardCnt() + 1);
        } else if (board.getBoardOrder() - orderRequestDto.getAfterOrder() > 0) {
            boardRepository.updateOrderSum(board.getBoardOrder(), orderRequestDto.getAfterOrder(), orderRequestDto.getFolderId());
        } else {
            boardRepository.updateOrderMinus(board.getBoardOrder(), orderRequestDto.getAfterOrder(), orderRequestDto.getFolderId());
        }

        board.updateOrder(orderRequestDto.getAfterOrder());
    }

    public FileUploadResponse boardImageUpload(MultipartFile imageFile) {
        return s3Uploader.upload(imageFile, BOARD.getPath());
    }

    @Transactional
    public Page<Board> findNewBoard(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdDate").descending());
        return boardRepository.findAllByStatus(DisclosureStatusType.PUBLIC, pageRequest);
    }

    public List<CategoryType> FolderRequestDtoToCategoryTypeList(List<FolderRequestDto> folderRequestDtos) {
        List<CategoryType> categoryTypeList = new ArrayList<>();
        for (FolderRequestDto folderRequestDto : folderRequestDtos) {
            categoryTypeList.add(folderRequestDto.getCategory());
        }
        return categoryTypeList;
    }

    public List<Board> findByFolder(Folder folder) {
        return boardRepository.findByFolder(folder);
    }

    @Transactional(readOnly = true)
    public FolderRequestDto moum(List<FolderRequestDto> folderRequestDtos,
                                 String keyword,
                                 HttpServletRequest request,
                                 Pageable pageable,
                                 Long folderId,
                                 Long userId) {
        List<DisclosureStatusType> disclosureStatusTypes = new ArrayList<>();
        disclosureStatusTypes.add(DisclosureStatusType.PUBLIC);

        Users user = userRepository.findById(userId)
                .orElseGet(() -> {
                    if (userId == 0L) {
                        disclosureStatusTypes.add(DisclosureStatusType.PRIVATE);
                        return userinfoHttpRequest.userFindByToken(request);
                    }
                    throw new RuntimeException("회원을 찾을 수 없습니다.");
                });

        return new FolderRequestDto(
                boardRepository.findByFolderIdAndTitleContainingAndCategoryIn(
                        folderId,
                        "%" + keyword + "%",
                        findSelectCategory(folderRequestDtos),
                        user.getId(),
                        disclosureStatusTypes,
                        pageable
                ),
                folderRepository.findById(folderId).get()
        );
    }

    public BoardAndCntResponseDto allBoards(String keyword, int page, List<FolderRequestDto> folderRequestDtos, HttpServletRequest request) {
        Users users = userinfoHttpRequest.userFindByToken(request);
        Optional<FolderRequestDto> all = folderRequestDtos.stream()
                .filter(categoryType -> categoryType.getCategory() == ALL)
                .findFirst();
        Page<Board> boards;
        PageRequest pageRequest = PageRequest.of(page, 8, Sort.by("createdDate").descending());
        if (all.isPresent()) {
            boards = boardRepository.findAllByStatusAndTitleContaining(DisclosureStatusType.PUBLIC,
                    "%" + keyword + "%",
                    users.getId(),
                    pageRequest
            );
        } else {
            boards = boardRepository.findAllByStatusAndTitleContainingAndCategoryIn(DisclosureStatusType.PUBLIC, "%" + keyword + "%", FolderRequestDtoToCategoryTypeList(folderRequestDtos), pageRequest);
        }
        return new BoardAndCntResponseDto(boards, boards.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<Map<String, CategoryType>> findCategoryList(Long userId, Long folderId, HttpServletRequest request) {
        Users user = userRepository.findById(userId)
                .orElseGet(() -> {
                    if (userId == 0L) {
                        return userinfoHttpRequest.userFindByToken(request);
                    }
                    throw new RuntimeException("회원을 찾을 수 없습니다.");
                });

        return findCategoryByUsersIdAndFolderId(user.getId(), folderId);
    }

    private List<Map<String, CategoryType>> findCategoryByUsersIdAndFolderId(Long id, Long folderId) {
        return ListToListMap(boardRepository.findCategoryByUsersIdAndFolderId(id, folderId));
    }

    public List<Map<String, CategoryType>> findCategoryByUsersId(Long id) {
        return ListToListMap(boardRepository.findAllCategoryByUsersId(id));
    }

    public List<Map<String, CategoryType>> ListToListMap(List<CategoryType> categoryList) {
        List<Map<String, CategoryType>> addList = new ArrayList<>();
        for (CategoryType categoryType : categoryList) {
            if (categoryType != CategoryType.NO_CATEGORY) {
                Map<String, CategoryType> adMap = new HashMap<>();
                adMap.put("category", categoryType);
                addList.add(adMap);
            }
        }
        return addList;
    }

    public List<CategoryType> findSelectCategory(List<FolderRequestDto> folderRequestDtos) {
        List<CategoryType> categoryTypeList = null;

        Optional<FolderRequestDto> findAllCategory = folderRequestDtos.stream()
                .filter(categoryType -> categoryType.getCategory() == ALL)
                .findFirst();

        if (findAllCategory.isPresent()) {
            categoryTypeList = ALL_CATEGORYT;
        } else {
            categoryTypeList = FolderRequestDtoToCategoryTypeList(folderRequestDtos);
        }
        return categoryTypeList;
    }

    @Transactional
    public void boardInFolder(Long folderId, FolderRequestDto folderRequestDto, HttpServletRequest request) {

        Folder afterFolder = findByIdAndUsersId(folderId, request);

        List<Board> afterBoard = findAllById(
                folderRequestDto.getBoardList().stream()
                        .map(Board::getId)
                        .collect(Collectors.toList())
        );

        Long beforeBoardId = boardRepository.findFolderIdById(folderRequestDto.getBoardList().get(0).getId())
                .orElseThrow(() -> new RuntimeException("없는 글입니다."));

        Long afterCnt = 1L;
        for (Board board : afterBoard) {
            board.addFolderId(afterFolder);
            board.updateOrder(afterFolder.getBoardCnt() + afterCnt);
            afterCnt++;
        }
        afterFolder.setBoardCnt(afterFolder.getBoardCnt() + afterBoard.size());

        Folder beforeFolder = findByIdAndUsersId(beforeBoardId, request);
        List<Board> beforeBoardList = boardRepository.findByFolder(beforeFolder);

        Long beforeCnt = 1L;
        for (Board folder : beforeBoardList) {
            folder.setBoardOrder(beforeCnt);
            beforeCnt++;
        }
        beforeFolder.setBoardCnt(beforeFolder.getBoardCnt() - afterBoard.size());
    }

    private Folder findByIdAndUsersId(Long folderId, HttpServletRequest request) {
        return folderRepository.findByIdAndUsersId(
                        folderId,
                        userinfoHttpRequest.userFindByToken(request).getId()
                )
                .orElseThrow(() -> new RuntimeException("FolderService 45 에러, 찾는 폴더가 없습니다."));
    }

    private void findImageEqualsDtoImage(List<Image> images, BoardRequestDto boardRequestDto, Board board) {
        for (Image image : images) {
            if (image.getImageType() == boardRequestDto.getImage().getImageType() && image.getBoard().getId().equals(board.getId())) {
                image.imagePathUpdate(boardRequestDto);
                break;
            }
        }
    }

    public MessageResponseDto findBoard(HttpServletRequest request, Long boardId) {
        Board board = boardRepository.findByIdAndUsers(
                        boardId,
                        userinfoHttpRequest.userFindByToken(request)
                )
                .orElseThrow(() ->
                        new CustomException(NOT_FIND_USER)
                );

        return new MessageResponseDto(
                200,
                "조회완료되었습니다.",
                new BoardResponseDto(
                        board,
                        imageRepository.findByBoard(board)
                )
        );

    }
}
