package com.hanghae99.finalproject.service;

import com.hanghae99.finalproject.model.dto.requestDto.*;
import com.hanghae99.finalproject.model.dto.responseDto.*;
import com.hanghae99.finalproject.model.entity.*;
import com.hanghae99.finalproject.model.repository.*;
import com.hanghae99.finalproject.util.UserinfoHttpRequest;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;

import java.util.*;

@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final FolderRepository folderRepository;
    private final UserinfoHttpRequest userinfoHttpRequest;

    @Transactional(readOnly = true)
    public FolderAndBoardResponseDto findMyFolderAndBoardList(HttpServletRequest request) {
        Users user = userinfoHttpRequest.userFindByToken(request);
        return new FolderAndBoardResponseDto(
                boardRepository.findByUsersIdAndFolderIdIsNull(user.getId()),
                folderRepository.findByUsersIdOnlyFolder(user.getId(), "all")
        );
    }

    @Transactional
    public Board boardSave(BoardRequestDto boardRequestDto, HttpServletRequest request) {
        Users user = userinfoHttpRequest.userFindByToken(request);
        System.out.println(user);
        return boardRepository.save(
                new Board(
                        boardRepository.findBoardCount(user.getId()),
                        boardRequestDto,
                        user
                )
        );
    }

    @Transactional
    public void boardUpdate(Long id, BoardRequestDto boardRequestDto, HttpServletRequest request) {
        Board board = boardFindById(id);

        userinfoHttpRequest.userAndWriterMatches(
                board.getUsers().getId(),
                userinfoHttpRequest.userFindByToken(request).getId()
        );

        board.update(boardRequestDto);
    }

    @Transactional
    public void boardDelete(Long id, HttpServletRequest request) {
        Board board = boardFindById(id);

        userinfoHttpRequest.userAndWriterMatches(
                board.getUsers().getId(),
                userinfoHttpRequest.userFindByToken(request).getId()
        );

        boardRepository.deleteById(id);
    }

    public Board boardFindById(Long id) {
        return boardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("BoardService 74 에러, 해당 글을 찾지 못했습니다."));
    }

    public List<Board> findAllById(List<Long> longs) {
        return boardRepository.findAllById(longs);
    }

    public void boardDeleteByFolderId(Long folderId) {
        boardRepository.deleteByFolderId(folderId);
    }

    public void statusUpdateByFolderId(Long id, FolderRequestDto folderRequestDto) {
        Board board = boardRepository.findByFolderId(id)
                .orElseThrow(() -> new IllegalArgumentException("BoardService 87 에러 해당 폴더를 찾지 못했습니다."));
        board.updateStatus(folderRequestDto);
    }

    public OgResponseDto thumbnailLoad(String url) {
        OgResponseDto ogResponseDto = new OgResponseDto();
        try {
            Document doc = Jsoup.connect(url).get();
            String title = doc.select("meta[property=og:title]").attr("content");
            String image = doc.select("meta[property=og:image]").attr("content");
            String description = doc.select("meta[property=og:description]").attr("content");
            ogResponseDto = new OgResponseDto(title, image, description);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }

        return ogResponseDto;

    }


    public Board findShareBoard(Long boardId, HttpServletRequest request){
        return boardRepository.findByIdAndUsersIdNot(boardId,userinfoHttpRequest.userFindByToken(request).getId()).orElseThrow(()
                -> new RuntimeException("원하는 폴더를 찾지 못했습니다."));
    }
    public void  cloneBoard (Long boardId,HttpServletRequest request){
        Users users = userinfoHttpRequest.userFindByToken(request);
        Board board = findShareBoard(boardId,request);
        BoardRequestDto boardRequestDto = new BoardRequestDto(board);
        Board board1 = new Board(boardRequestDto, users);
        boardRepository.save(board1);
    }

    @Transactional
    public void boardOrderChange(FolderAndBoardRequestDto folderAndBoardRequestDto, HttpServletRequest request) {
        List<BoardRequestDto> dbList = toBoardRequestDtoList(
                boardRepository.findByUsers(
                        userinfoHttpRequest.userFindByToken(request)
                )
        );

        for (BoardRequestDto boardRequestDto : folderAndBoardRequestDto.getBoardList()) {
            for (BoardRequestDto dbDto : dbList) {
                if (boardRequestDto.getId() == dbDto.getId()) {
                    if (boardRequestDto.getBoardOrder() != dbDto.getBoardOrder()) {
                        Board targetBoard = boardFindById(boardRequestDto.getId());
                        targetBoard.updateOrder(boardRequestDto.getBoardOrder());
                    }
                }
            }
        }
    }

    private List<BoardRequestDto> toBoardRequestDtoList(List<Board> boards) {
        List<BoardRequestDto> boardRequestDtoList = new ArrayList<>();

        for (Board board : boards) {
            boardRequestDtoList.add(new BoardRequestDto(board));
        }
        return boardRequestDtoList;
    }


}
